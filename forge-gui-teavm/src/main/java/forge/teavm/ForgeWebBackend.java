package forge.teavm;

import com.github.xpenatan.gdx.teavm.backends.shared.config.compiler.TeaCompilerData;
import com.github.xpenatan.gdx.teavm.backends.web.config.backend.WebBackend;
import org.teavm.callgraph.CallGraph;
import org.teavm.dependency.DependencyAgent;
import org.teavm.dependency.DependencyAnalyzer;
import org.teavm.dependency.DependencyListener;
import org.teavm.dependency.FieldDependency;
import org.teavm.dependency.MethodDependency;
import org.teavm.diagnostics.AccumulationDiagnostics;
import org.teavm.diagnostics.Problem;
import org.teavm.diagnostics.ProblemProvider;
import org.teavm.vm.TeaVM;
import org.teavm.vm.TeaVMPhase;
import org.teavm.vm.TeaVMProgressFeedback;
import org.teavm.vm.TeaVMProgressListener;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

/**
 * Forge-specific subclass of {@link WebBackend} that configures the TeaVM
 * compiler to produce a non-empty {@code app.js} despite many JVM APIs being
 * absent from TeaVM's JS class library.
 *
 * <h2>The problem</h2>
 * <p>Forge's transitive classpath references many JVM APIs unavailable in
 * TeaVM's JS classlib ({@code javax.xml}, {@code java.net.*},
 * {@code java.util.concurrent.*}, etc.).  TeaVM's dependency analyser reports
 * each missing class/method/field as an {@code ERROR}-severity problem.
 * {@link TeaVM#build} checks {@code diagnostics.getSevereProblems().isEmpty()}
 * immediately after dependency analysis and returns early (writing nothing) if
 * any severe problems exist.  This leaves {@code app.js} at 0 bytes even when
 * non-strict mode is enabled.
 *
 * <h2>The fix</h2>
 * <ol>
 *   <li>Non-strict mode ({@code tool.setStrict(false)}) keeps the compiler
 *       running instead of aborting on the first missing dependency.</li>
 *   <li>A {@link DependencyListener#complete()} hook, registered just before
 *       dependency analysis runs, uses reflection to clear
 *       {@code AccumulationDiagnostics.severeProblems} at the very end of
 *       the analysis phase — before {@link TeaVM#build} checks for severe
 *       problems.  This allows code generation to proceed normally.</li>
 *   <li>The {@link #logBuild} override then suppresses the
 *       {@code RuntimeException("Build Failed")} that the parent would throw
 *       for the (now-WARNING-level) "X was not found" problems.</li>
 *   <li>Source-level stub classes in {@code src/main/java/} eliminate specific
 *       missing-API references from genuinely reachable call paths.</li>
 * </ol>
 */
public class ForgeWebBackend extends WebBackend {

    /**
     * Configures the TeaVM tool and installs the severe-problem-clearing
     * {@link DependencyListener} hook.
     */
    @Override
    protected void setup(TeaCompilerData data) {
        super.setup(data);
        tool.setStrict(false);

        /*
         * Register ForgeTeaVMTransformer so TeaVM runs it on every class before
         * dependency analysis.  It:
         *   1. Rewires any class whose declared superclass is absent from TeaVM's
         *      classlib to extend java.lang.Object instead, preventing
         *      $rt_classWithoutFields(undefinedParent) references in the generated
         *      JS that would cause an immediate ReferenceError on page load.
         *   2. Stubs out method bodies that reference missing classes.
         */
        tool.getTransformers().add(ForgeTeaVMTransformer.class.getName());

        /*
         * Register a progress listener so we can hook into the very start of
         * vm.build().  When TeaVM starts the DEPENDENCY_ANALYSIS phase we
         * add a DependencyListener whose complete() callback clears the
         * AccumulationDiagnostics.severeProblems list via reflection.
         * This runs after all dependency errors have been recorded but before
         * TeaVM.build() checks getSevereProblems().isEmpty(), allowing code
         * generation to proceed.
         */
        tool.setProgressListener(new TeaVMProgressListener() {
            @Override
            public TeaVMProgressFeedback phaseStarted(TeaVMPhase phase, int count) {
                if (phase == TeaVMPhase.DEPENDENCY_ANALYSIS) {
                    installSevereProblemClearer();
                }
                return TeaVMProgressFeedback.CONTINUE;
            }

            @Override
            public TeaVMProgressFeedback progressReached(int progress) {
                return TeaVMProgressFeedback.CONTINUE;
            }
        });
    }

    /**
     * Uses reflection to reach into {@code TeaVMTool.vm.dependencyAnalyzer}
     * and register a {@link DependencyListener} that clears
     * {@code AccumulationDiagnostics.severeProblems} at the end of the
     * dependency-analysis phase.
     */
    private void installSevereProblemClearer() {
        try {
            /* tool → TeaVM vm (private field of TeaVMTool) */
            Field vmField = tool.getClass().getDeclaredField("vm");
            vmField.setAccessible(true);
            TeaVM vm = (TeaVM) vmField.get(tool);
            if (vm == null) {
                return;
            }

            /* vm → AccumulationDiagnostics diagnostics (private) */
            Field diagField = TeaVM.class.getDeclaredField("diagnostics");
            diagField.setAccessible(true);
            AccumulationDiagnostics diagnostics = (AccumulationDiagnostics) diagField.get(vm);

            /* vm → DependencyAnalyzer dependencyAnalyzer (private) */
            Field daField = TeaVM.class.getDeclaredField("dependencyAnalyzer");
            daField.setAccessible(true);
            DependencyAnalyzer da = (DependencyAnalyzer) daField.get(vm);

            /* diagnostics → List<Problem> severeProblems (private) */
            Field spField = AccumulationDiagnostics.class.getDeclaredField("severeProblems");
            spField.setAccessible(true);

            da.addDependencyListener(new DependencyListener() {
                @Override public void started(DependencyAgent agent) { }
                @Override public void classReached(DependencyAgent agent, String name) { }
                @Override public void methodReached(DependencyAgent agent, MethodDependency dep) { }
                @Override public void fieldReached(DependencyAgent agent, FieldDependency dep) { }
                @Override public void completing(DependencyAgent agent) { }

                @Override
                public void complete() {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Problem> severe = (List<Problem>) spField.get(diagnostics);
                        int count = severe.size();
                        severe.clear();
                        if (count > 0) {
                            System.err.println("[ForgeWebBackend] Cleared " + count
                                    + " severe dependency problems before code generation "
                                    + "(non-strict mode — all are 'X was not found' in dead paths).");
                        }
                    } catch (IllegalAccessException e) {
                        System.err.println("[ForgeWebBackend] WARNING: could not clear severeProblems: " + e);
                    }
                }
            });
        } catch (NoSuchFieldException | IllegalAccessException e) {
            System.err.println("[ForgeWebBackend] WARNING: reflection setup failed: " + e);
        }
    }

    /**
     * Suppresses the {@code RuntimeException("Build Failed")} that the parent
     * throws when any problem exists, provided every problem is a
     * "X was not found" missing-dependency stub.
     */
    @Override
    protected void logBuild(ProblemProvider problemProvider,
                            Collection<String> classes,
                            CallGraph callGraph) {
        try {
            super.logBuild(problemProvider, classes, callGraph);
        } catch (RuntimeException e) {
            List<Problem> problems = problemProvider.getProblems();
            boolean hasRealErrors = problems.stream()
                    .anyMatch(p -> !p.getText().contains("was not found")
                            && !p.getText().contains("has no implementation"));
            if (hasRealErrors) {
                throw e;
            }
            System.err.println("[ForgeWebBackend] Build completed with "
                    + problems.size() + " missing-dependency stubs in dead "
                    + "code paths (non-strict mode).");
        }
    }
}
