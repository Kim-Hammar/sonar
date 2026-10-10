/-
Prints the axioms that each formalized statement of the paper depends on; `check.sh` fails if
any is not one of Lean's standard axioms. Add a `#print axioms` line for every theorem that
corresponds to a statement in the paper.
-/
import Sonar

-- Proposition 1: existence of an optimal search policy.
#print axioms Sonar.SearchModel.exists_optimal_policy

-- Proposition 2: correctness of the reduction from min-sum set cover.
#print axioms Sonar.MinSumSetCover.Instance.searchCost_policyOf_le
#print axioms Sonar.MinSumSetCover.Instance.mssc_extract_le
#print axioms Sonar.MinSumSetCover.Instance.extract_optimal
#print axioms Sonar.MinSumSetCover.Instance.searchCost_optimal_eq

-- Proposition 3: a step of SONAR takes O(n) operations and computes rules (5)-(6) and the update.
#print axioms Sonar.Step.sonarStep_linear
#print axioms Sonar.Step.sonarStep_val
