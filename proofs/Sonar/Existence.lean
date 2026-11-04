/-
# Existence of an optimal search policy (Proposition 3)

The search model of Section 3, abstracted to what the proposition needs. A search state `s` holds
the belief `b_t` and the configuration `c_t`; `info s` is what the policy observes, i.e., the pair
`(b_t, c_t)`, and `violated s` is `g_Φ(c_t)`. A decision is a region and a number of candidate
repairs, `(i, r) : Region × ℕ`, with `r ≤ r_max`. After a decision, the search returns one of
finitely many outcomes `o : O` (`z_t` in the paper), with probability `prob s d o`, and moves to
`next s d o`.

The search stops at the first step with `r_t = 0` or `g_Φ(c_t) = 0`; its cost is the sum of
`r_t + κ` over the steps before that one, plus `λ g_Φ(c_τ)` (objective (5) with stopping time (2)).
Here `J_T(μ)` is `costUpTo μ T` and `J(μ) = sup_T J_T(μ)` is `cost μ`.

Proposition 3 is `SearchModel.exists_optimal_policy`: among the policies `(i_t, r_t) = μ(b_t, c_t)`,
one minimizes the expected cost from the initial state, and its cost is finite. The proof follows
the paper: the policies form a compact space (Tychonoff), on which `J` is lower semicontinuous as
the supremum of the `J_T`, each of which depends on finitely many decisions and is hence continuous.
-/
import Mathlib

open scoped ENNReal NNReal
open Filter Topology

namespace Sonar

/-- The search model of Section 3 (see the module documentation). -/
structure SearchModel (S Info Region O : Type*) where
  /-- What the policy observes in a search state: the pair `(b_t, c_t)`. -/
  info : S → Info
  /-- `g_Φ(c_t)`: the number of specifications that the configuration of a state violates. -/
  violated : S → ℕ
  /-- The state after decision `d = (i, r)` and search outcome `o`. -/
  next : S → Region × ℕ → O → S
  /-- The probability of search outcome `o` after decision `d` in a state. -/
  prob : S → Region × ℕ → O → ℝ≥0∞
  /-- The weight `λ` of each specification left violated when the search stops. -/
  lam : ℝ≥0
  /-- The per-step cost `κ` of invoking the search mechanism. -/
  κ : ℝ≥0
  /-- The largest number of candidate repairs `r_max` verified in one step. -/
  rmax : ℕ

/-- A deterministic search policy `(i_t, r_t) = μ(b_t, c_t)`. -/
abbrev Policy (Info Region : Type*) := Info → Region × ℕ

namespace SearchModel

variable {S Info Region O : Type*} [Fintype O] (M : SearchModel S Info Region O)

/-- Whether the search stops in state `s` with decision `d`: `r = 0` or the network is
repaired. -/
def Stops (s : S) (d : Region × ℕ) : Prop := d.2 = 0 ∨ M.violated s = 0

instance (s : S) (d : Region × ℕ) : Decidable (M.Stops s d) := by
  unfold Stops; infer_instance

/-- `J_T(μ)` from state `s`: the expected cost of the first `T` steps, including the stopping
cost if the search stops within them. -/
noncomputable def costUpTo (μ : Policy Info Region) : ℕ → S → ℝ≥0∞
  | 0, _ => 0
  | T + 1, s =>
    if M.Stops s (μ (M.info s)) then
      M.lam * M.violated s
    else
      ((μ (M.info s)).2 : ℝ≥0∞) + M.κ +
        ∑ o, M.prob s (μ (M.info s)) o * costUpTo μ T (M.next s (μ (M.info s)) o)

/-- `J(μ)` from state `s`: the expected cost (5) of policy `μ`. -/
noncomputable def cost (μ : Policy Info Region) (s : S) : ℝ≥0∞ := ⨆ T, M.costUpTo μ T s

/-- A policy of the paper verifies at most `r_max` candidate repairs per step. -/
def Admissible (μ : Policy Info Region) : Prop := ∀ b, (μ b).2 ≤ M.rmax

/-! ### Continuity of `J_T` on a finite decision set -/

section Continuity

variable {D : Type*} [TopologicalSpace D] [DiscreteTopology D] (e : D → Region × ℕ)

/-- `J_T` depends only on finitely many decisions: near any policy `ν` over a discrete decision
set `D`, it is constant. -/
theorem eventually_costUpTo_eq (T : ℕ) :
    ∀ (s : S) (ν : Info → D), ∀ᶠ ν' in 𝓝 ν,
      M.costUpTo (e ∘ ν') T s = M.costUpTo (e ∘ ν) T s := by
  induction T with
  | zero => intro s ν; exact Eventually.of_forall fun _ => rfl
  | succ T ih =>
    intro s ν
    have h1 : ∀ᶠ ν' in 𝓝 ν, ν' (M.info s) = ν (M.info s) :=
      (continuous_apply (M.info s)).continuousAt.preimage_mem_nhds
        ((isOpen_discrete {ν (M.info s)}).mem_nhds rfl)
    have h2 : ∀ᶠ ν' in 𝓝 ν, ∀ o,
        M.costUpTo (e ∘ ν') T (M.next s (e (ν (M.info s))) o) =
          M.costUpTo (e ∘ ν) T (M.next s (e (ν (M.info s))) o) :=
      eventually_all.2 fun o => ih _ ν
    filter_upwards [h1, h2] with ν' h1' h2'
    simp only [costUpTo, Function.comp_apply, h1', h2']

theorem continuous_costUpTo (T : ℕ) (s : S) :
    Continuous fun ν : Info → D => M.costUpTo (e ∘ ν) T s :=
  continuous_iff_continuousAt.2 fun ν =>
    tendsto_nhds_of_eventually_eq (M.eventually_costUpTo_eq e T s ν)

end Continuity

/-! ### Proposition 3 -/

/-- Stopping immediately costs `λ g_Φ(c_1)`. -/
theorem cost_stop (i : Region) (s : S) : M.cost (fun _ => (i, 0)) s = M.lam * M.violated s := by
  apply le_antisymm
  · refine iSup_le fun T => ?_
    cases T with
    | zero => simp [costUpTo]
    | succ T => simp [costUpTo, Stops]
  · refine le_iSup_of_le 1 ?_
    simp [costUpTo, Stops]

/-- **Proposition 3.** With finitely many search regions and finitely many search outcomes per
step, some policy that verifies at most `r_max` candidates per step minimizes the expected cost (5)
from the initial state `s₁` over all such policies, and its cost is finite. -/
theorem exists_optimal_policy [Finite Region] [Nonempty Region] (s₁ : S) :
    ∃ μstar : Policy Info Region, M.Admissible μstar ∧ M.cost μstar s₁ < ⊤ ∧
      ∀ μ : Policy Info Region, M.Admissible μ → M.cost μstar s₁ ≤ M.cost μ s₁ := by
  let D := Region × Fin (M.rmax + 1)
  letI : TopologicalSpace D := ⊥
  haveI : DiscreteTopology D := ⟨rfl⟩
  let e : D → Region × ℕ := fun d => (d.1, d.2.val)
  let F : (Info → D) → ℝ≥0∞ := fun ν => M.cost (e ∘ ν) s₁
  have hF : LowerSemicontinuous F :=
    lowerSemicontinuous_iSup fun T => (M.continuous_costUpTo e T s₁).lowerSemicontinuous
  obtain ⟨i₀⟩ := ‹Nonempty Region›
  have hne : (Set.univ : Set (Info → D)).Nonempty := ⟨fun _ => (i₀, 0), trivial⟩
  obtain ⟨ν, -, hν⟩ := (hF.lowerSemicontinuousOn Set.univ).exists_isMinOn hne isCompact_univ
  have hmin : ∀ μ : Policy Info Region, M.Admissible μ → M.cost (e ∘ ν) s₁ ≤ M.cost μ s₁ := by
    intro μ hμ
    let ν' : Info → D := fun b => ((μ b).1, ⟨(μ b).2, Nat.lt_succ_of_le (hμ b)⟩)
    have hν' : e ∘ ν' = μ := rfl
    calc M.cost (e ∘ ν) s₁ ≤ M.cost (e ∘ ν') s₁ := hν (Set.mem_univ ν')
      _ = M.cost μ s₁ := by rw [hν']
  refine ⟨e ∘ ν, fun b => Nat.lt_succ_iff.1 (ν b).2.isLt, ?_, hmin⟩
  calc M.cost (e ∘ ν) s₁ ≤ M.cost (fun _ => (i₀, 0)) s₁ := hmin _ fun _ => Nat.zero_le _
    _ = M.lam * M.violated s₁ := M.cost_stop i₀ s₁
    _ < ⊤ := ENNReal.mul_lt_top ENNReal.coe_lt_top (ENNReal.natCast_lt_top _)

end SearchModel

end Sonar
