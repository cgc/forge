package forge.teavm;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * End-to-end smoke test that verifies the compiled Forge TeaVM web app boots
 * inside a headless Chromium browser without fatal JavaScript errors.
 *
 * <h2>Two-level readiness model</h2>
 * <ol>
 *   <li><b>Boot signal</b> ({@code window.__forgeStarted}) – set by
 *       {@link TeaVMLauncher} in the first {@code ApplicationListener.create()}
 *       call, <em>before</em> any Forge assets are loaded.  Verifies that the
 *       compiled JavaScript executes and the LibGDX WebApplication lifecycle
 *       starts.  Does <em>not</em> require the card database or skin assets.
 *       Timeout: {@value #START_TIMEOUT_MS} ms.</li>
 *   <li><b>Home-screen signal</b> ({@code window.__forgeReady}) – set after
 *       {@code Forge.afterDBloaded} becomes {@code true}, i.e. the full card
 *       database has been loaded and the home screen is visible.  Requires the
 *       complete Forge asset set.  This check is skipped unless the system
 *       property {@code forge.e2e.fullAssets=true} is set.
 *       Timeout: {@value #LOAD_TIMEOUT_MS} ms.</li>
 * </ol>
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Run the TeaVM build first:
 *       <pre>  mvn -pl forge-gui-teavm -Pteavm package [-Dforge.assetsDir=…]</pre>
 *       If the compiled output is absent the test is automatically skipped.</li>
 *   <li>Install Playwright browsers (one-time, for CI use):
 *       <pre>  java -cp playwright.jar:driver-bundle.jar com.microsoft.playwright.CLI install chromium</pre>
 *       Playwright will automatically download its bundled Chromium on first
 *       use if neither a manual install nor a system browser is detected.</li>
 * </ol>
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>A JDK {@link HttpServer} serves the compiled {@code target/dist/webapp/}
 *       directory over HTTP on an OS-assigned ephemeral port.</li>
 *   <li>A Chromium browser (Playwright Java) loads the page.  Runs headless
 *       by default; pass {@code -Dforge.e2e.headed=true} to open a visible
 *       window for interactive DevTools inspection.</li>
 *   <li>Every browser console message is printed to stdout in real-time as it
 *       arrives, so fatal JavaScript errors are immediately visible in CI logs
 *       and local test runs alike.</li>
 *   <li>The test polls {@code window.__forgeStarted} every
 *       {@value #POLL_INTERVAL_MS} ms up to {@value #START_TIMEOUT_MS} ms.</li>
 *   <li>All browser console errors are captured; if any uncaught JavaScript
 *       error occurs the test fails with the full error text so the root cause
 *       is visible without digging through logs.</li>
 * </ol>
 *
 * <h2>Local developer debug loop</h2>
 * <p>Run with a headed (visible) browser to interactively watch the console:
 * <pre>
 *   mvn -pl forge-gui-teavm -Pteavm verify -Dforge.e2e.headed=true
 * </pre>
 * The browser window stays open for the duration of the test, letting you
 * inspect the DevTools console alongside the real-time stdout output.
 */
@Test(groups = {"teavm-smoke"})
public class HomeScreenLoadTest {

    /** Path to the compiled webapp, relative to the module's working directory. */
    private static final String WEBAPP_DIR = "target/dist/webapp";

    /**
     * Early boot flag set in {@code create()} before asset loading.
     * Does not require the card database.
     */
    private static final String STARTED_FLAG = "window.__forgeStarted";

    /**
     * Home-screen flag set after the full DB loads.
     * Only checked when {@code forge.e2e.fullAssets=true}.
     */
    private static final String READY_FLAG = "window.__forgeReady";

    /** Timeout (ms) for the early boot signal (no assets needed). */
    private static final int START_TIMEOUT_MS = 30_000;

    /** Timeout (ms) for the full home-screen ready signal (needs full assets). */
    private static final int LOAD_TIMEOUT_MS = 120_000;

    /** Poll interval (ms) when waiting for readiness flags. */
    private static final int POLL_INTERVAL_MS = 500;

    private HttpServer httpServer;
    private int serverPort;
    private Playwright playwright;
    private Browser browser;

    @BeforeClass
    public void setUp() throws IOException {
        Path webappPath = Paths.get(WEBAPP_DIR);
        if (!Files.exists(webappPath.resolve("index.html"))) {
            throw new SkipException(
                "TeaVM build output not found at " + webappPath.toAbsolutePath()
                + " – run 'mvn -Pteavm package' first.");
        }

        httpServer = startFileServer(webappPath);
        serverPort = httpServer.getAddress().getPort();

        playwright = Playwright.create();
        boolean headed = Boolean.parseBoolean(
                System.getProperty("forge.e2e.headed", "false"));
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions()
                .setHeadless(!headed)
                .setArgs(List.of(
                    "--disable-gpu",
                    "--no-sandbox",
                    "--disable-dev-shm-usage"
                )));
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /**
     * Primary smoke test: verifies the Forge TeaVM JavaScript compiles,
     * loads in a headless browser, and reaches the LibGDX {@code create()}
     * lifecycle phase without fatal errors.
     *
     * <p>This test does <em>not</em> require the card database or skin assets.
     * It is the fast CI gate: pass means the compiled JavaScript is valid and
     * the core LibGDX/TeaVM bootstrap works.
     *
     * <p>If {@code forge.e2e.fullAssets=true} is set and the full asset set is
     * present, this test <em>also</em> waits for {@code window.__forgeReady}
     * (the home screen with DB loaded).
     */
    @Test
    public void forgeBootsWithoutFatalErrors() throws InterruptedException {
        BrowserContext ctx = browser.newContext();
        Page page = ctx.newPage();

        List<ConsoleMessage> consoleMsgs = new ArrayList<>();
        // Collects the text of every "fatal" browser error so the assertion
        // failure message shows exactly what went wrong (not just a boolean flag).
        List<String> fatalErrors = Collections.synchronizedList(new ArrayList<>());

        page.onConsoleMessage(msg -> {
            String type = msg.type();
            String text = msg.text();
            // Print every message in real-time so errors appear immediately
            // in CI logs and local terminal output without waiting for the
            // timeout to expire.
            System.out.printf("[browser:%s] %s%n", type, text);
            consoleMsgs.add(msg);
            // "error" type covers console.error() calls.
            // "startGroupCollapsed" is how TeaVM's fatal-error handler logs
            // via console.groupCollapsed("%cFatal Error: ...", "color:#FF0000").
            boolean isFatal = (type.equals("error") || type.equals("startGroupCollapsed"))
                    && (text.contains("Fatal Error")
                    || text.contains("Uncaught")
                    || text.contains("SyntaxError")
                    || text.contains("TypeError"));
            if (isFatal) {
                fatalErrors.add(text);
            }
        });

        page.onPageError(err -> {
            // onPageError delivers uncaught JS exceptions as a String.
            System.err.printf("[PageError] %s%n", err);
            fatalErrors.add(err);
        });

        // Intercept console.groupCollapsed before app.js loads so we can
        // capture the JavaScript call stack for fatal errors.
        page.addInitScript(
            "const _ogGroupCollapsed = console.groupCollapsed.bind(console);\n" +
            "console.groupCollapsed = function(...args) {\n" +
            "  const text = String(args[0] || '');\n" +
            "  if (text.includes('Fatal Error')) {\n" +
            "    const stack = new Error('FatalErrorLocation').stack;\n" +
            "    console.error('FATAL_STACK: ' + stack);\n" +
            "  }\n" +
            "  return _ogGroupCollapsed(...args);\n" +
            "};\n"
        );

        page.navigate("http://localhost:" + serverPort + "/");

        // Wait for the early boot signal (fires in create(), before asset loading).
        boolean started = waitForFlag(page, STARTED_FLAG, START_TIMEOUT_MS);

        // Real-time output already printed each message as it arrived; emit a
        // compact summary line so the total counts are easy to spot in logs.
        long errorCount = consoleMsgs.stream().filter(m -> m.type().equals("error")).count();
        System.out.printf("%n=== Browser console summary: %d messages (%d errors) ===%n%n",
                consoleMsgs.size(), errorCount);

        Assert.assertTrue(fatalErrors.isEmpty(),
            "Fatal JavaScript error(s) detected in the browser console:\n\n"
            + String.join("\n\n", fatalErrors));

        Assert.assertTrue(started,
            "Forge did not reach ApplicationListener.create() within "
            + START_TIMEOUT_MS + " ms.  app.js may have failed to load or "
            + "thrown a JavaScript exception.  See console output above.");

        // Optionally wait for the full home screen (requires card database).
        boolean fullAssetsMode = Boolean.parseBoolean(
                System.getProperty("forge.e2e.fullAssets", "false"));
        if (fullAssetsMode) {
            boolean ready = waitForFlag(page, READY_FLAG, LOAD_TIMEOUT_MS);
            Assert.assertTrue(ready,
                "Forge home screen did not appear within " + LOAD_TIMEOUT_MS
                + " ms even though forge.e2e.fullAssets=true.  "
                + "Check the console output above for runtime errors.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Polls a boolean JavaScript expression every {@link #POLL_INTERVAL_MS} ms
     * until it evaluates to {@code true} or {@code timeoutMs} elapses.
     */
    private static boolean waitForFlag(Page page, String jsExpr, int timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Object result = page.evaluate("() => !!(typeof " + jsExpr.replace("window.", "")
                    + " !== 'undefined' && " + jsExpr + ")");
            if (Boolean.TRUE.equals(result)) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return false;
    }

    /**
     * Starts a minimal JDK {@link HttpServer} that serves static files from
     * {@code webappPath} on an OS-assigned ephemeral port.
     */
    private static HttpServer startFileServer(Path webappPath) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String uriPath = exchange.getRequestURI().getPath();
            if (uriPath.equals("/")) {
                uriPath = "/index.html";
            }
            Path filePath = webappPath.resolve(uriPath.substring(1)).normalize();
            if (!filePath.startsWith(webappPath)) {
                exchange.sendResponseHeaders(403, 0);
                exchange.getResponseBody().close();
                return;
            }
            if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                byte[] body = "404 Not Found".getBytes();
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            String contentType = guessContentType(filePath.toString());
            byte[] bytes = Files.readAllBytes(filePath);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.setExecutor(null);
        server.start();
        return server;
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }
}
