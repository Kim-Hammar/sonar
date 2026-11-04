/-
Prints the axioms that each formalized statement of the paper depends on; `check.sh` fails if
any is not one of Lean's standard axioms. Add a `#print axioms` line for every theorem that
corresponds to a statement in the paper.
-/
import Sonar

-- Proposition 1: a step of SONAR takes O(n) operations and computes rules (6a)-(6c) and the belief.
#print axioms Sonar.Step.rule_spec
#print axioms Sonar.Step.sonarStep_linear
#print axioms Sonar.Step.sonarStep_val
#print axioms Sonar.Step.sonarInitStep_linear
#print axioms Sonar.Step.sonarInitStep_val

-- Proposition 2: soundness (a) and completeness (b) of SONAR.
#print axioms Sonar.RepairProcess.g_step_le
#print axioms Sonar.RepairProcess.g_iterate_le
#print axioms Sonar.RepairProcess.valid_repair_within
#print axioms Sonar.RepairProcess.terminates
#print axioms Sonar.RepairProcess.terminates_within

-- Proposition 3: existence of an optimal search policy.
#print axioms Sonar.SearchModel.exists_optimal_policy

-- Proposition 4: correctness of the reduction from min-sum set cover.
#print axioms Sonar.MinSumSetCover.Instance.searchCost_policyOf_le
#print axioms Sonar.MinSumSetCover.Instance.mssc_extract_le
#print axioms Sonar.MinSumSetCover.Instance.extract_optimal
#print axioms Sonar.MinSumSetCover.Instance.searchCost_optimal_eq
