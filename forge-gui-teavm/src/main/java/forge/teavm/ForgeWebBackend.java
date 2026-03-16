package forge.teavm;

import com.github.xpenatan.gdx.teavm.backends.shared.config.compiler.TeaCompilerData;
import com.github.xpenatan.gdx.teavm.backends.web.config.backend.WebBackend;
import org.teavm.callgraph.CallGraph;
import org.teavm.diagnostics.Problem;
import org.teavm.diagnostics.ProblemProvider;

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

        /*
         * Register the Forge-specific ClassHolderTransformer.  It patches
         * ForgeProfileProperties and FileUtil BEFORE TeaVM's dependency
         * analysis, removing JVM API calls (File.toPath, System.getenv) that
         * are absent from TeaVM's JS classlib.  Without this, those references
         * land in AccumulationDiagnostics.getSevereProblems() and cause
         * TeaVM.build() to return early without emitting any JavaScript.
         *
         * TeaVMTool.getTransformers() is a List<String> of class names; the
         * tool loads them with its own classloader (which includes the
         * forge-gui-teavm JAR) and invokes transformClass() on each class.
         */
        tool.getTransformers().add(ForgeTeaVMTransformer.class.getName());
    }

    /**
     * Overrides the parent's log-and-fail behaviour to suppress compilation
     * failures that are caused only by missing classes / methods / fields in
     * dead-code paths reachable from the Forge dependency tree.
     *
     * <p>In non-strict mode ({@link #setup}), TeaVM still records dependency
     * problems at {@code ERROR} severity in the problem provider.  The parent's
     * {@code logBuild} unconditionally throws {@code RuntimeException("Build
     * Failed")} when any problem exists.
     *
     * <p>This override intercepts that exception, inspects each problem's
     * template text, and only re-throws if there are problems that are
     * <em>not</em> of the "X was not found" pattern (i.e., real compilation
     * errors in code that the Forge web target actually calls).  The "was not
     * found" problems all correspond to dead-code paths – they will never be
     * executed in a browser, and TeaVM replaces them with
     * {@code throw new UnsupportedOperationException()} stubs in the generated JS.
     *
     * <p>Template strings used by TeaVM's dependency checker:
     * <ul>
     *   <li>{@code "Class \{\{c0\}\} was not found"}</li>
     *   <li>{@code "Method \{\{m0\}\} was not found"}</li>
     *   <li>{@code "Field \{\{f0\}\} was not found"}</li>
     * </ul>
     */
    @Override
    protected void logBuild(ProblemProvider problemProvider,
                            Collection<String> classes,
                            CallGraph callGraph) {
        try {
            super.logBuild(problemProvider, classes, callGraph);
        } catch (RuntimeException e) {
            /*
             * If every problem is just a "something was not found" message
             * (dead code / unresolvable dependency in the classpath), treat
             * the build as successful.  TeaVM has already inserted throw stubs
             * at those call sites so the generated JS is valid.
             * If ANY problem has a different text (a real compile error),
             * propagate the failure.
             */
            List<Problem> problems = problemProvider.getProblems();
            boolean hasRealErrors = problems.stream()
                    .anyMatch(p -> !p.getText().contains("was not found"));
            if (hasRealErrors) {
                throw e;
            }
            System.err.println("[ForgeWebBackend] Build completed with "
                    + problems.size() + " missing-dependency stubs in dead "
                    + "code paths (non-strict mode).");
        }
    }
}
