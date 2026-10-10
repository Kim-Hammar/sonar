/-
# Existence of an optimal search policy (Proposition 1)

The search model of Section 3, abstracted to what the proposition needs. A search state `s`
holds the belief `b_t` and the configuration `c_t`; `violated s` is `g_Φ(c_t)`. A decision is a
region and a number of candidates, `(x, r) : X × ℕ`. After a decision, the verifier returns one
of finitely many outcomes `o : O`, with probability `prob s d o`, and the search moves to
`next s d o`. Only valid repairs are applied, so no step increases `g_Φ`.

The search stops at the first step with `r_t = 0` or `g_Φ(c_t) = 0`; its cost is the sum of the
`r_t` up to and including that step, plus `λ g_Φ(c_τ)` (objective (1) with stopping time (2)).
Here `J_T(μ)` is `costUpTo μ T` and `J(μ) = sup_T J_T(μ)` is `cost μ`.

Proposition 1 is `SearchModel.exists_optimal_policy`: among the deterministic policies
`(x_t, r_t) = μ(b_t)`, one minimizes the expected cost from the initial state. The proof follows
the paper: verifying more than `λ g_Φ(c_1)` candidates in one step costs more than stopping,
so it suffices to minimize over policies with `r_t ≤ R = ⌈λ g_Φ(c_1)⌉`; these form a compact
space (Tychonoff), on which `J` is lower semicontinuous as the supremum of the `J_T`, each of
which depends on finitely many decisions and is hence continuous.
-/
import Mathlib

open scoped ENNReal NNReal
open Filter Topology

namespace Sonar

/-- The search model of Section 3 (see the module documentation). -/
structure SearchModel (S B X O : Type*) where
  /-- The belief `b_t` of a search state. -/
  belief : S → B
  /-- `g_Φ(c_t)`: the number of specifications that the configuration of a state violates. -/
  violated : S → ℕ
  /-- The state after decision `d = (x, r)` and verification outcome `o`. -/
  next : S → X × ℕ → O → S
  /-- The probability of verification outcome `o` after decision `d` in a state. -/
  prob : S → X × ℕ → O → ℝ≥0∞
  /-- The weight `λ` of each specification left violated when the search stops. -/
  lam : ℝ≥0
  /-- Only valid repairs are applied, so no step increases the number of violations. -/
  violated_next_le : ∀ s d o, violated (next s d o) ≤ violated s

/-- A deterministic search policy `(x_t, r_t) = μ(b_t)`. -/
abbrev Policy (B X : Type*) := B → X × ℕ

namespace SearchModel

variable {S B X O : Type*} [Fintype O] (M : SearchModel S B X O)

/-- Whether the search stops in state `s` with decision `d`: `r = 0` or the network is
repaired. -/
def Stops (s : S) (d : X × ℕ) : Prop := d.2 = 0 ∨ M.violated s = 0

instance (s : S) (d : X × ℕ) : Decidable (M.Stops s d) := by
  unfold Stops; infer_instance

/-- `J_T(μ)` from state `s`: the expected cost of the first `T` steps, including the stopping
cost if the search stops within them. -/
noncomputable def costUpTo (μ : Policy B X) : ℕ → S → ℝ≥0∞
  | 0, _ => 0
  | T + 1, s =>
    if M.Stops s (μ (M.belief s)) then
      ((μ (M.belief s)).2 : ℝ≥0∞) + M.lam * M.violated s
    else
      ((μ (M.belief s)).2 : ℝ≥0∞) +
        ∑ o, M.prob s (μ (M.belief s)) o * costUpTo μ T (M.next s (μ (M.belief s)) o)

/-- `J(μ)` from state `s`: the expected cost (1) of policy `μ`. -/
noncomputable def cost (μ : Policy B X) (s : S) : ℝ≥0∞ := ⨆ T, M.costUpTo μ T s

/-- Each step costs at least the number of candidates it verifies. -/
theorem le_costUpTo_succ (μ : Policy B X) (T : ℕ) (s : S) :
    ((μ (M.belief s)).2 : ℝ≥0∞) ≤ M.costUpTo μ (T + 1) s := by
  rw [costUpTo]
  split_ifs <;> exact le_self_add

/-! ### Bounding the number of candidates verified per step -/

/-- `μ` with every decision to verify more than `R` candidates replaced by stopping. -/
def truncate (R : ℕ) (μ : Policy B X) : Policy B X := fun b =>
  if (μ b).2 ≤ R then μ b else ((μ b).1, 0)

theorem truncate_le (R : ℕ) (μ : Policy B X) (b : B) : (truncate R μ b).2 ≤ R := by
  unfold truncate
  split_ifs with h
  · exact h
  · exact Nat.zero_le R

/-- Truncating at `R ≥ λ g_Φ(c_1)` does not increase the cost of the first `T` steps from any
state with at most `g_Φ(c_1)` violations. -/
theorem costUpTo_truncate_le {s₁ : S} {R : ℕ}
    (hR : (M.lam : ℝ≥0∞) * M.violated s₁ ≤ R) (μ : Policy B X) :
    ∀ (T : ℕ) (s : S), M.violated s ≤ M.violated s₁ →
      M.costUpTo (truncate R μ) T s ≤ M.costUpTo μ T s := by
  intro T
  induction T with
  | zero => intro s _; simp [costUpTo]
  | succ T ih =>
    intro s hs
    by_cases h : (μ (M.belief s)).2 ≤ R
    · have hd : truncate R μ (M.belief s) = μ (M.belief s) := by simp [truncate, h]
      rw [costUpTo, costUpTo, hd]
      split_ifs
      · exact le_rfl
      · gcongr with o
        exact ih _ ((M.violated_next_le _ _ _).trans hs)
    · have hstop : M.Stops s (truncate R μ (M.belief s)) := Or.inl (by simp [truncate, h])
      rw [costUpTo, if_pos hstop]
      have hd : (truncate R μ (M.belief s)).2 = 0 := by simp [truncate, h]
      calc ((truncate R μ (M.belief s)).2 : ℝ≥0∞) + M.lam * M.violated s
          = M.lam * M.violated s := by rw [hd]; simp
        _ ≤ M.lam * M.violated s₁ := by gcongr
        _ ≤ R := hR
        _ ≤ (μ (M.belief s)).2 := by exact_mod_cast (not_le.mp h).le
        _ ≤ M.costUpTo μ (T + 1) s := M.le_costUpTo_succ μ T s

theorem cost_truncate_le {s₁ : S} {R : ℕ} (hR : (M.lam : ℝ≥0∞) * M.violated s₁ ≤ R)
    (μ : Policy B X) : M.cost (truncate R μ) s₁ ≤ M.cost μ s₁ :=
  iSup_mono fun T => M.costUpTo_truncate_le hR μ T s₁ le_rfl

/-! ### Continuity of `J_T` on a finite decision set -/

section Continuity

variable {D : Type*} [TopologicalSpace D] [DiscreteTopology D] (e : D → X × ℕ)

/-- `J_T` depends only on finitely many decisions: near any policy `ν` over a discrete decision
set `D`, it is constant. -/
theorem eventually_costUpTo_eq (T : ℕ) :
    ∀ (s : S) (ν : B → D), ∀ᶠ ν' in 𝓝 ν,
      M.costUpTo (e ∘ ν') T s = M.costUpTo (e ∘ ν) T s := by
  induction T with
  | zero => intro s ν; exact Eventually.of_forall fun _ => rfl
  | succ T ih =>
    intro s ν
    have h1 : ∀ᶠ ν' in 𝓝 ν, ν' (M.belief s) = ν (M.belief s) :=
      (continuous_apply (M.belief s)).continuousAt.preimage_mem_nhds
        ((isOpen_discrete {ν (M.belief s)}).mem_nhds rfl)
    have h2 : ∀ᶠ ν' in 𝓝 ν, ∀ o,
        M.costUpTo (e ∘ ν') T (M.next s (e (ν (M.belief s))) o) =
          M.costUpTo (e ∘ ν) T (M.next s (e (ν (M.belief s))) o) :=
      eventually_all.2 fun o => ih _ ν
    filter_upwards [h1, h2] with ν' h1' h2'
    simp only [costUpTo, Function.comp_apply, h1', h2']

theorem continuous_costUpTo (T : ℕ) (s : S) :
    Continuous fun ν : B → D => M.costUpTo (e ∘ ν) T s :=
  continuous_iff_continuousAt.2 fun ν =>
    tendsto_nhds_of_eventually_eq (M.eventually_costUpTo_eq e T s ν)

end Continuity

/-! ### Proposition 1 -/

/-- **Proposition 1.** With finitely many search regions and finitely many verification
outcomes per step, some deterministic search policy minimizes the expected cost (1) from the
initial state `s₁` over all deterministic policies. -/
theorem exists_optimal_policy [Finite X] [Nonempty X] (s₁ : S) :
    ∃ μstar : Policy B X, ∀ μ : Policy B X, M.cost μstar s₁ ≤ M.cost μ s₁ := by
  set R : ℕ := ⌈M.lam * (M.violated s₁ : ℝ≥0)⌉₊
  have hR : (M.lam : ℝ≥0∞) * M.violated s₁ ≤ R := by
    exact_mod_cast Nat.le_ceil (M.lam * (M.violated s₁ : ℝ≥0))
  -- The policies that verify at most `R` candidates per step, over a finite discrete decision set.
  let D := X × Fin (R + 1)
  letI : TopologicalSpace D := ⊥
  haveI : DiscreteTopology D := ⟨rfl⟩
  let e : D → X × ℕ := fun d => (d.1, d.2.val)
  let F : (B → D) → ℝ≥0∞ := fun ν => M.cost (e ∘ ν) s₁
  have hF : LowerSemicontinuous F :=
    lowerSemicontinuous_iSup fun T => (M.continuous_costUpTo e T s₁).lowerSemicontinuous
  obtain ⟨x₀⟩ := ‹Nonempty X›
  have hne : (Set.univ : Set (B → D)).Nonempty := ⟨fun _ => (x₀, 0), trivial⟩
  obtain ⟨ν, -, hν⟩ := (hF.lowerSemicontinuousOn Set.univ).exists_isMinOn hne isCompact_univ
  refine ⟨e ∘ ν, fun μ => ?_⟩
  -- Any policy is matched by its truncation, which verifies at most `R` candidates per step.
  let ν' : B → D := fun b =>
    ((truncate R μ b).1, ⟨(truncate R μ b).2, Nat.lt_succ_of_le (truncate_le R μ b)⟩)
  have hν' : e ∘ ν' = truncate R μ := rfl
  calc M.cost (e ∘ ν) s₁ ≤ M.cost (e ∘ ν') s₁ := hν (Set.mem_univ ν')
    _ = M.cost (truncate R μ) s₁ := by rw [hν']
    _ ≤ M.cost μ s₁ := M.cost_truncate_le hR μ

end SearchModel

end Sonar
