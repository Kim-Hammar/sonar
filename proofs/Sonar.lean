/-
The Lean formalization of the theoretical results of the SONAR paper. Each module formalizes one
statement or one piece of the model; this root module imports them all, so `lake build` checks
every proof.
-/
import Sonar.Existence
import Sonar.Reduction
import Sonar.Complexity
import Sonar.Completeness
