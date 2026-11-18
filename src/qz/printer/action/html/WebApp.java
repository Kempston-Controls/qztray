package qz.printer.action.html;

import com.github.zafarkhaja.semver.Version;
import com.sun.javafx.scene.NodeHelper;
import com.sun.javafx.scene.SceneHelper;
import com.sun.javafx.tk.TKPulseListener;
import com.sun.javafx.tk.Toolkit;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Transform;
import javafx.scene.transform.Translate;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import qz.App;
import qz.common.Constants;
import qz.utils.ArgValue;
import qz.utils.PrefsSearch;
import qz.utils.SystemUtilities;
import qz.ws.PrintSocketServer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;

/**
 * JavaFX container for taking HTML snapshots.
 * Used by PrintHTML to generate printable images.
 * <p/>
 * Do not use constructor (used by JavaFX), instead call {@code WebApp.initialize()}
 */
public class WebApp extends Application {
    public static double getZoom() {
        return pageZoom;
    }

    private static final Logger log = LogManager.getLogger(WebApp.class);

    private static WebApp instance = null;
    private static Version webkitVersion = null;
    private static int CAPTURE_FRAMES = 2;
    private static int VECTOR_FRAMES = 1;
    private static Stage stage;
    private static WebView webView;
    private static double pageWidth;
    private static double pageHeight;
    private static double pageZoom;
    private static boolean headless;

    private static CountDownLatch startupLatch;
    private static CountDownLatch captureLatch;
    // token for the job currently using the WebView; null when idle or aborted, so late events from an old job are ignored
    private static volatile Object activeJob = null;
    // the job whose page has already loaded and is being captured/printed, so a page that reloads itself can't start it twice
    private static Object renderingJob = null;
    // set (on the JavaFX thread) while a new job stops whatever the WebView was still doing, so that cancellation isn't blamed on the new job
    private static boolean replacingPage = false;

    private static IntPredicate printAction;
    private static final AtomicReference<Throwable> thrown = new AtomicReference<>();

    // JDK-8283686: Printing WebView may results in empty page
    private static final Version JDK_8283686_START = Version.valueOf(/* WebKit */ "609.1.0");
    private static final Version JDK_8283686_END = Version.valueOf(/* WebKit */ "612.1.0");
    private static final int JDK_8283686_VECTOR_FRAMES = 30;


    //listens for a Succeeded state to activate image capture
    private static ChangeListener<Worker.State> stateListener = (ov, oldState, newState) -> {
        log.trace("New state: {} > {}", oldState, newState);
        if (activeJob == null) {
            log.trace("Ignoring state {}, no active job", newState);
            return;
        }
        if (renderingJob == activeJob) {
            // The page already loaded and is being captured/printed, but it's navigating again (e.g. scripts calling
            // location.reload()). Stop that navigation so the loaded page stays in place, and don't let it affect the job.
            if (newState == Worker.State.SCHEDULED) {
                log.warn("Page started loading again while being captured (it may reload itself), stopping it");
                final Object job = activeJob;
                Platform.runLater(() -> {
                    if (job == activeJob) { webView.getEngine().getLoadWorker().cancel(); }
                });
            }
            log.trace("Ignoring state {}, page is already being captured", newState);
            return;
        }

        // Cancelled should probably throw exception listener, but does not
        if (newState == Worker.State.CANCELLED) {
            if (replacingPage) {
                // The previous page (e.g. one that reloads itself, or the blank page left by the last job) was still
                // loading when this job started; stopping it is expected and isn't a failure of this job
                log.trace("Stopped previous page load to start the next job");
                return;
            }
            // This can happen for file downloads, e.g. "response-content-disposition=attachment"
            // See https://github.com/qzind/tray/issues/1183
            unlatch(activeJob, new IOException("Page load was cancelled for an unknown reason"));
        }
        if (newState == Worker.State.SUCCEEDED) {
            boolean hasBody = (boolean)webView.getEngine().executeScript("document.body != null");
            if (!hasBody) {
                log.warn("Loaded page has no body - likely a redirect, skipping state");
                return;
            }

            //width was resized earlier (for responsive html), then calculate the best fit height
            // FIXME: Should only be needed when height is unknown but fixes blank vector prints
            double fittedHeight = findHeight();
            boolean heightNeeded = pageHeight <= 0;

            if (heightNeeded) {
                pageHeight = fittedHeight;
            }

            // find and set page zoom for increased quality
            double usableZoom = calculateSupportedZoom(pageWidth, pageHeight);
            if (usableZoom < pageZoom) {
                log.warn("Zoom level {} decreased to {} due to physical memory limitations", pageZoom, usableZoom);
                pageZoom = usableZoom;
            }
            webView.setZoom(pageZoom);
            log.trace("Zooming in by x{} for increased quality", pageZoom);

            adjustSize(pageWidth * pageZoom, pageHeight * pageZoom);

            //need to check for height again as resizing can cause partial results
            if (heightNeeded) {
                fittedHeight = findHeight();
                if (fittedHeight != pageHeight) {
                    adjustSize(pageWidth * pageZoom, fittedHeight * pageZoom);
                }
            }

            log.trace("Set HTML page height to {}", pageHeight);

            webView.autosize();

            // bind the timer to this job, so it stops if the job is aborted or replaced
            final Object job = activeJob;
            final IntPredicate action = printAction;
            renderingJob = job;
            Platform.runLater(() -> new AnimationTimer() {
                int frames = 0;

                @Override
                public void handle(long l) {
                    if (job != activeJob || action.test(++frames)) {
                        stop();
                    }
                }
            }.start());
        }
    };

    //listens for load progress
    private static ChangeListener<Number> workDoneListener = (ov, oldWork, newWork) -> log.trace("Done: {} > {}", oldWork, newWork);

    private static ChangeListener<String> msgListener = (ov, oldMsg, newMsg) -> log.trace("New status: {}", newMsg);

    //listens for failures
    private static ChangeListener<Throwable> exceptListener = (obs, oldExc, newExc) -> {
        if (newExc == null) { return; }
        if (activeJob != null && renderingJob == activeJob) {
            // a later navigation by the page failed, the page being captured is unaffected
            log.warn("Ignoring page load error after the page loaded: {}", newExc.toString());
            return;
        }
        unlatch(activeJob, newExc);
    };


    /** Called by JavaFX thread */
    public WebApp() {
        instance = this;
    }

    /** Starts JavaFX thread if not already running */
    public static synchronized void initialize() throws IOException {
        if (instance == null) {
            startupLatch = new CountDownLatch(1);
            // For JDK8 compat
            headless = false;

            // JDK11+ depends bundled javafx
            if (Constants.JAVA_VERSION.getMajorVersion() >= 11) {
                // Monocle default for unit tests
                boolean useMonocle = true;
                if (PrintSocketServer.getTrayManager() != null) {
                    // Honor user monocle override
                    useMonocle = PrintSocketServer.getTrayManager().isMonoclePreferred();
                    // Trust TrayManager's headless detection
                    headless = App.isHeadless();
                } else {
                    // Fallback for JDK11+
                    headless = true;
                }
                if (useMonocle) {
                    if(SystemUtilities.hasMonocle()) {
                        // Legacy "Monocle" mode
                        System.setProperty("javafx.platform", "monocle");
                        // Don't set glass.platform on Linux per https://github.com/qzind/tray/issues/702
                        switch(SystemUtilities.getOs()) {
                            case WINDOWS:
                            case MAC:
                                System.setProperty("glass.platform", "Monocle");
                                break;
                            default:
                                // don't set "glass.platform"
                        }

                        //software rendering required headless environments
                        if (headless) {
                            System.setProperty("prism.order", "sw");
                        }
                    } else {
                        // Assume newer "Headless" mode is available
                        System.setProperty("glass.platform", "Headless");
                        System.setProperty("prism.order", "sw"); // Per JDK-8382148
                    }
                    log.trace("Initializing {} glass.platform", SystemUtilities.hasMonocle() ? "monocle" : "headless");
                } else {
                    log.warn("{} glass.platform will not be used", SystemUtilities.hasMonocle() ? "Monocle" : "Headless");
                }
            }

            new Thread(() -> Application.launch(WebApp.class)).start();
        }

        if (startupLatch.getCount() > 0) {
            try {
                log.trace("Waiting for JavaFX..");
                if (!startupLatch.await(60, TimeUnit.SECONDS)) {
                    throw new IOException("JavaFX did not start");
                } else {
                    log.trace("Running a test snapshot to size the stage...");
                    try {
                        raster(new WebAppModel("<h1>startup</h1>", true, 0, 0, true, 2));
                        log.trace("JFX initialized successfully");
                    }
                    catch(Throwable t) {
                        throw new IOException(t);
                    }
                }
            }
            catch(InterruptedException ignore) {}
        }
    }

    @Override
    public void start(Stage st) throws Exception {
        startupLatch.countDown();
        log.debug("Started JavaFX");

        webView = new WebView();

        // JDK-8283686: Printing WebView may results in empty page
        // See also https://github.com/qzind/tray/issues/778
        if(getWebkitVersion() == null ||
                (getWebkitVersion().greaterThan(JDK_8283686_START) &&
                        getWebkitVersion().lessThan(JDK_8283686_END))) {
            VECTOR_FRAMES = JDK_8283686_VECTOR_FRAMES; // Additional pulses needed for vector graphics
        }

        st.setScene(new Scene(webView));
        stage = st;
        stage.setWidth(1);
        stage.setHeight(1);

        Worker<Void> worker = webView.getEngine().getLoadWorker();
        worker.stateProperty().addListener(stateListener);
        worker.workDoneProperty().addListener(workDoneListener);
        worker.exceptionProperty().addListener(exceptListener);
        worker.messageProperty().addListener(msgListener);

        //prevents JavaFX from shutting down when hiding window
        Platform.setImplicitExit(false);

        // hide webview scrollbars whenever they appear
        webView.getChildrenUnmodifiable().addListener((ListChangeListener<Node>)change -> {
            Set<Node> nodeSet = webView.lookupAll(".scroll-bar");
            nodeSet.forEach(scroll -> scroll.setVisible(false));
        });
    }

    /**
     * Prints the loaded source specified in the passed {@code model}.
     *
     * @param job   A setup JavaFx {@code PrinterJob}
     * @param model The model specifying the web page parameters
     * @throws Throwable JavaFx will throw a generic {@code Throwable} class for any issues
     */
    public static synchronized void print(final PrinterJob job, final WebAppModel model) throws Throwable {
        model.setZoom(1); //vector prints do not need to use zoom
        final Object token = new Object();

        // prevents blank pages
        Platform.runLater(() -> {
            stage.show();
            stage.toBack();
        });

        load(model, token, (int frames) -> {
            if(frames >= VECTOR_FRAMES) {
                try {
                    double printScale = 72d / 96d;
                    webView.getTransforms().add(new Scale(printScale, printScale));

                    PageLayout layout = job.getJobSettings().getPageLayout();
                    if (model.isScaled()) {
                        double viewWidth = webView.getWidth() * printScale;
                        double viewHeight = webView.getHeight() * printScale;

                        double scale;
                        if ((viewWidth / viewHeight) >= (layout.getPrintableWidth() / layout.getPrintableHeight())) {
                            scale = (layout.getPrintableWidth() / viewWidth);
                        } else {
                            scale = (layout.getPrintableHeight() / viewHeight);
                        }
                        webView.getTransforms().add(new Scale(scale, scale));
                    }

                    Platform.runLater(() -> {
                        Exception possiblyThrown = null;
                        double useScale = 1;
                        for(Transform t : webView.getTransforms()) {
                            if (t instanceof Scale) { useScale *= ((Scale)t).getX(); }
                        }

                        PageLayout page = job.getJobSettings().getPageLayout();
                        Rectangle printBounds = new Rectangle(0, 0, page.getPrintableWidth(), page.getPrintableHeight());
                        log.debug("Paper area: {},{}:{},{}", (int)page.getLeftMargin(), (int)page.getTopMargin(),
                                  (int)page.getPrintableWidth(), (int)page.getPrintableHeight());

                        Translate activePage = new Translate();
                        webView.getTransforms().add(activePage);

                        int columnsNeed = Math.max(1, (int)Math.ceil(webView.getWidth() / printBounds.getWidth() * useScale - 0.1));
                        int rowsNeed = Math.max(1, (int)Math.ceil(webView.getHeight() / printBounds.getHeight() * useScale - 0.1));
                        log.debug("Document will be printed across {} pages", columnsNeed * rowsNeed);

                        try {
                            for(int row = 0; row < rowsNeed; row++) {
                                for(int col = 0; col < columnsNeed; col++) {
                                    activePage.setX((-col * printBounds.getWidth()) / useScale);
                                    activePage.setY((-row * printBounds.getHeight()) / useScale);

                                    job.printPage(webView);
                                }
                            }
                        } catch(Exception e) {
                            possiblyThrown = e;
                        } finally {
                            webView.getTransforms().clear();
                        }
                        unlatch(token, possiblyThrown);
                    });
                } catch(Exception e) {
                    unlatch(token, e);
                }
            }
            return frames >= VECTOR_FRAMES;
        });

        log.trace("Waiting on print..");
        awaitCompletion(model);
    }

    public static synchronized BufferedImage raster(final WebAppModel model) throws Throwable {
        AtomicReference<BufferedImage> capture = new AtomicReference<>();
        final Object job = new Object();

        //ensure JavaFX has started before we run
        if (startupLatch.getCount() > 0) {
            throw new IOException("JavaFX has not been started");
        }

        //raster still needs to show stage for valid capture
        Platform.runLater(() -> {
            stage.show();
            stage.toBack();
        });

        load(model, job, (int frames) -> {
            if (frames == CAPTURE_FRAMES) {
                log.debug("Attempting image capture");

                Toolkit.getToolkit().addPostSceneTkPulseListener(new TKPulseListener() {
                    @Override
                    public void pulse() {
                        Exception possiblyThrown = null;
                        try {
                            // TODO: Revert to Callback once JDK-8244588/SUPQZ-5 is avail (JDK11+ only)
                            capture.set(SwingFXUtils.fromFXImage(webView.snapshot(null, null), null));
                        }
                        catch(Exception t) {
                            possiblyThrown = t;
                        }
                        finally {
                            Toolkit.getToolkit().removePostSceneTkPulseListener(this);
                        }
                        unlatch(job, possiblyThrown);
                    }
                });
                Toolkit.getToolkit().requestNextPulse();
            }

            return frames >= CAPTURE_FRAMES;
        });

        log.trace("Waiting on capture..");
        awaitCompletion(model);

        return capture.get();
    }

    /**
     * Prints the loaded source specified in the passed {@code model}.
     *
     * @param model  The model specifying the web page parameters.
     * @param job    Token identifying this job; callbacks from any other (aborted) job are ignored.
     * @param action EventHandler that will be ran when the WebView completes loading.
     */
    private static synchronized void load(WebAppModel model, Object job, IntPredicate action) {
        captureLatch = new CountDownLatch(1);
        thrown.set(null);
        activeJob = job;

        Platform.runLater(() -> {
            if (activeJob != job) {
                log.warn("HTML job was aborted before it started loading");
                return;
            }

            //zoom should only be factored on raster prints
            pageZoom = model.getZoom();
            pageWidth = model.getWebWidth();
            pageHeight = model.getWebHeight();

            log.trace("Setting starting size {}:{}", pageWidth, pageHeight);
            adjustSize(pageWidth * pageZoom, Math.max(pageHeight * pageZoom, 1));

            webView.autosize();

            printAction = action;

            // Stopping any load still in progress (and the one replaced by the load below) fires CANCELLED, which
            // belongs to the previous page, not to this job
            replacingPage = true;
            try {
                webView.getEngine().getLoadWorker().cancel();
                if (model.isPlainText()) {
                    webView.getEngine().loadContent(model.getSource(), "text/html");
                } else {
                    webView.getEngine().load(model.getSource());
                }
            }
            finally {
                replacingPage = false;
            }
        });
    }

    /**
     * Waits for the whole job (page load, render and capture/print) to finish, bounded by {@code print.html.timeout}.
     * If the job is still running when the timeout expires, it is aborted regardless of its state, so that
     * subsequent HTML jobs are never blocked behind it (e.g. a script or resource that never responds).
     */
    private static void awaitCompletion(WebAppModel model) throws Throwable {
        int timeout = PrefsSearch.getInt(ArgValue.PRINT_HTML_TIMEOUT, App.getTrayProperties());
        if (timeout > 0) {
            if (!captureLatch.await(timeout, TimeUnit.SECONDS)) {
                String source = model.isPlainText()? "HTML content":model.getSource();
                log.error("HTML job for {} did not finish within {} seconds, aborting", source, timeout);
                abort();
                throw new IOException(String.format("HTML job did not finish within %s seconds and was aborted. " +
                                                            "Check the page for scripts or resources that never respond (%s)",
                                                    timeout, source));
            }
        } else {
            captureLatch.await(); //released when unlatch is called
        }

        if (thrown.get() != null) { throw thrown.get(); }
    }

    /**
     * Abandons the active job whatever state it is in, and resets the WebView so it's ready for the next job
     */
    private static void abort() {
        activeJob = null; // from here on, any callbacks from the abandoned job are ignored

        CountDownLatch aborted = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                webView.getEngine().getLoadWorker().cancel();
                webView.getTransforms().clear();
                clearPage();
                stage.hide();
            }
            catch(Throwable t) {
                log.warn("Failed to cleanly abort HTML job", t);
            }
            finally {
                aborted.countDown();
            }
        });

        try {
            if (!aborted.await(10, TimeUnit.SECONDS)) {
                log.warn("JavaFX thread did not respond while aborting HTML job; it may be blocked by the page or printer");
            }
        }
        catch(InterruptedException ignore) {}
    }

    private static double findHeight() {
        String heightText = webView.getEngine().executeScript("document.body.scrollHeight").toString();
        return Double.parseDouble(heightText);
    }

    private static void adjustSize(double toWidth, double toHeight) {
        webView.setMinSize(toWidth, toHeight);
        webView.setPrefSize(toWidth, toHeight);
        webView.setMaxSize(toWidth, toHeight);
        doUpdatePeer();
    }

    private static void doUpdatePeer() {
        // Call updatePeer; fixes a bug with webView resizing
        SceneHelper.setAllowPGAccess(true);
        NodeHelper.updatePeer(webView);
        SceneHelper.setAllowPGAccess(false);
    }

    private static double calculateSupportedZoom(double width, double height) {
        long memory = Runtime.getRuntime().maxMemory();
        int allowance = (memory / 1048576L) > 1024? 3:2;
        if (headless) { allowance--; }
        long availSpace = memory << allowance;

        // Memory needed for print is roughly estimated as
        // (width * height) [pixels needed] * (pageZoom * 72d) [print density used] * 3 [rgb channels]
        return Math.sqrt(availSpace / ((width * height) * (pageZoom * 72d) * 3));
    }

    /**
     * Final cleanup when no longer capturing
     */
    public static void unlatch(Throwable t) {
        unlatch(activeJob, t);
    }

    private static void unlatch(Object job, Throwable t) {
        if (job == null || job != activeJob) {
            log.debug("Ignoring completion of an aborted HTML job", t);
            return;
        }
        activeJob = null;

        if (t != null) {
            thrown.set(t);
        }

        clearPage();
        captureLatch.countDown();
        stage.hide();
    }

    /**
     * Replaces the page with a blank one, so the finished page's scripts stop running between jobs
     * (e.g. pages that keep reloading themselves or polling a server that isn't there).
     * Load events from the blank page are ignored, as no job is active.
     */
    private static void clearPage() {
        // Deferred, as this can be called from within a pulse; runs before the next job's load, which is queued after it
        Platform.runLater(() -> {
            if (activeJob != null) { return; } // the next job has already started
            try {
                webView.getEngine().loadContent("");
            }
            catch(Throwable t) {
                log.warn("Failed to clear the HTML page", t);
            }
        });
    }

    public static Version getWebkitVersion() {
        if(webkitVersion == null) {
            if(webView != null) {
                String userAgent = webView.getEngine().getUserAgent();
                String[] parts = userAgent.split("WebKit/");
                if (parts.length > 1) {
                    String[] split = parts[1].split(" ");
                    if (split.length > 0) {
                        try {
                            webkitVersion = Version.parse(split[0], false);
                            log.info("WebKit version {} detected", webkitVersion);
                        } catch(Exception ignore) {}
                    }
                }
                if(webkitVersion == null) {
                    log.warn("WebKit version couldn't be parsed from UserAgent: {}", userAgent);
                }
            } else {
                log.warn("Can't get WebKit version, JavaFX hasn't started yet.");
            }
        }
        return webkitVersion;
    }
}