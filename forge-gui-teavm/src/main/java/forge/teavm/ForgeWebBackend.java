package forge.teavm;

import com.github.xpenatan.gdx.teavm.backends.shared.config.compiler.TeaCompilerData;
import com.github.xpenatan.gdx.teavm.backends.web.config.backend.WebBackend;
import org.teavm.callgraph.CallGraph;
import org.teavm.diagnostics.Problem;
import org.teavm.diagnostics.ProblemProvider;
import org.teavm.diagnostics.ProblemSeverity;

import java.util.Collection;
import java.util.List;

/**
 * Forge-specific subclass of {@link WebBackend} that configures the TeaVM
 * compiler in non-strict mode.
 *
 * <h2>Why non-strict mode?</h2>
 * <p>Forge depends on many libraries (Guava, log4j, Java concurrent, Java
 * serialization, javax.xml, jupnp, etc.) that use APIs not available in
 * TeaVM's JavaScript class library.  In the browser, all those code paths are
 * dead: they are either guarded by platform checks (e.g.
 * {@code isRunningOnDesktop()} returning {@code false} for the web target), or
 * they belong to desktop-only features like UPnP port-mapping or threaded
 * networking that are simply not wired up in {@link TeaVMLauncher}.
 *
 * <p>TeaVM's default <em>strict</em> mode treats any unresolvable dependency as a
 * hard error that aborts compilation.  <em>Non-strict</em> mode
 * ({@code TeaVMTool.setStrict(false)}) downgrades those to warnings and replaces
 * the unreachable call sites with {@code throw new UnsupportedOperationException()}
 * in the generated JavaScript.  If a guarded dead-code path is mistakenly
 * reached at runtime, the app throws a clear exception rather than silently
 * misbehaving.
 *
 * <h2>What IS handled by stubs (not non-strict)</h2>
 * <p>Dependencies that are genuinely reachable in the browser are handled by
 * proper no-op stub classes in {@code src/main/java/}:
 * <ul>
 *   <li>{@code io.sentry.*} / {@code io.sentry.protocol.*}</li>
 *   <li>{@code org.jupnp.UpnpService} and {@code UpnpServiceConfiguration}</li>
 * </ul>
 */
public class ForgeWebBackend extends WebBackend {

    /**
     * Calls the parent {@code setup} method to perform the standard web-target
     * configuration, then switches the TeaVM compiler to non-strict mode so
     * that missing JVM APIs from dead code paths are warnings rather than
     * hard errors.
     *
     * <p>The {@code tool} field is {@code protected} in {@link
     * com.github.xpenatan.gdx.teavm.backends.shared.config.compiler.TeaBackend}
     * and is fully initialised by the time {@code setup} is invoked (it is set
     * up in the {@code final compile()} method before {@code setup} is called).
     */
    @Override
    protected void setup(TeaCompilerData data) {
        super.setup(data);
        /*
         * Non-strict: missing classes / methods in dead code paths become
         * warnings + runtime UnsupportedOperationException stubs rather than
         * hard build errors.  This lets the compilation succeed for the Forge
         * web target despite the many server-side / desktop-only dependencies
         * in the transitive classpath.
         */
        tool.setStrict(false);
    }

    /**
     * Overrides the parent's log-and-fail behaviour to only treat
     * {@link ProblemSeverity#ERROR} diagnostics as build failures.
     *
     * <p>The parent's {@code logBuild} fails the build if the TeaVM problem
     * provider contains <em>any</em> problems, including {@code WARNING}-level
     * ones.  In non-strict mode ({@link #setup}), missing dependencies are
     * downgraded to {@code WARNING}; calling the parent as-is would still abort
     * the build despite a successful compilation.  This override passes the
     * call through to the parent for display, then only re-throws the
     * "Build Failed" exception when actual {@code ERROR}-level problems exist.
     */
    @Override
    protected void logBuild(ProblemProvider problemProvider,
                            Collection<String> classes,
                            CallGraph callGraph) {
        List<Problem> problems = problemProvider.getProblems();
        boolean hasErrors = problems.stream()
                .anyMatch(p -> p.getSeverity() == ProblemSeverity.ERROR);
        try {
            super.logBuild(problemProvider, classes, callGraph);
        } catch (RuntimeException e) {
            if (hasErrors) {
                throw e;   // real errors → propagate
            }
            // only warnings (non-strict missing deps) → compilation succeeded
            System.err.println("[ForgeWebBackend] Build completed with "
                    + problems.size() + " warning(s); no errors.");
        }
    }
}
