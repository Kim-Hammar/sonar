/-
# Soundness and completeness of SONAR (Proposition 2)

The repair process of SONAR, abstracted to what the proposition needs. A configuration `c` has
`g c` violated specifications (`g_Φ(c)`) and a finite search space `space c` of candidate repairs
(the candidates of the regions in `X(c)`); `valid c a` holds when verification shows that candidate `a`
reduces the number of violated specifications. The state of the search is the configuration and
the candidates verified for it so far.

At each step the search policy selects a batch of candidates that have not been verified for the
configuration (verified candidates are excluded from later selection), at least `minBatch ≥ 1` of
them or all that remain. The paper's condition `r_t ≥ 1` is the case `minBatch = 1`, which also
covers a batch cut short because its region is exhausted (a region whose candidates are exhausted
is removed). The selection rule is otherwise arbitrary, so the results hold for every belief,
decision model and policy that does not stop prematurely: the search stops only once `g = 0`. If the batch holds a valid repair, SONAR
adopts a repair verified to reduce `g` (one valid candidate, several combined, or a replacement)
and regenerates the search space for the new configuration; otherwise it records the batch as
verified and continues.

* `g_step_le`, `g_iterate_le`: **(a)** a step never increases the number of violations.
* `epoch_bound`: if the search space holds a valid repair, one is adopted within
  `⌈N_t / minBatch⌉` steps, where `N_t` is the number of candidates in `X(c_t)`.
* `terminates`, `terminates_within`: **(b)** if every search space with `g > 0` holds a valid
  repair, the search reaches `g = 0` after finitely many steps, at most `g(c₁) ⌈N / minBatch⌉` when
  every search space has at most `N` candidates.
-/
import Mathlib

open Classical

namespace Sonar

/-- The repair process of SONAR (see the module documentation). -/
structure RepairProcess (C A : Type*) where
  /-- `g_Φ(c)`: the number of specifications that configuration `c` violates. -/
  g : C → ℕ
  /-- The candidate repairs of the search space of configuration `c`. -/
  space : C → Finset A
  /-- Whether candidate `a` is a valid repair of `c`, as established by verification. -/
  valid : C → A → Prop
  /-- The configuration after adopting a repair, given a verified batch that holds one. -/
  adopt : C → Finset A → C
  /-- Only repairs verified to reduce the number of violations are adopted. -/
  adopt_lt : ∀ c B, (∃ a ∈ B, valid c a) → g (adopt c B) < g c
  /-- The batch of candidates the policy selects, given the candidates verified so far. -/
  select : C → Finset A → Finset A
  /-- A lower bound on the batch size (`r_t ≥ 1` in the paper is `minBatch = 1`). -/
  minBatch : ℕ
  /-- At least one candidate is verified per step. -/
  one_le_minBatch : 1 ≤ minBatch
  /-- Verified candidates are excluded from selection. -/
  select_subset : ∀ c T, select c T ⊆ space c \ T
  /-- A batch holds at least `minBatch` candidates, or all that remain. -/
  select_card : ∀ c T, min minBatch (space c \ T).card ≤ (select c T).card

namespace RepairProcess

variable {C A : Type*} (P : RepairProcess C A)

/-- One step of the search from configuration `s.1` with verified candidates `s.2`. -/
noncomputable def step (s : C × Finset A) : C × Finset A :=
  if P.g s.1 = 0 then s
  else if ∃ a ∈ P.select s.1 s.2, P.valid s.1 a then (P.adopt s.1 (P.select s.1 s.2), ∅)
  else (s.1, s.2 ∪ P.select s.1 s.2)

/-! ### (a) Soundness -/

/-- **Proposition 2 (a).** A step never increases the number of violated specifications. -/
theorem g_step_le (s : C × Finset A) : P.g (P.step s).1 ≤ P.g s.1 := by
  unfold step
  split_ifs with h0 hv
  · exact le_rfl
  · exact (P.adopt_lt _ _ hv).le
  · exact le_rfl

/-- Along the whole search, the number of violated specifications never increases. -/
theorem g_iterate_le (n : ℕ) (s : C × Finset A) : P.g (P.step^[n] s).1 ≤ P.g s.1 := by
  induction n generalizing s with
  | zero => exact le_rfl
  | succ n ih =>
    rw [Function.iterate_succ_apply]
    exact (ih _).trans (P.g_step_le s)

/-! ### A valid repair is found within `⌈N_t / minBatch⌉` steps -/

/-- From configuration `c` with verified candidates `T`, none of them valid, a valid repair in the
search space is adopted within `k` steps whenever `k minBatch` candidates cover the unverified ones;
the search then continues from a fresh search space of a configuration with fewer violations. -/
theorem epoch_bound (c : C) (hg : 0 < P.g c) (hvalid : ∃ a ∈ P.space c, P.valid c a) :
    ∀ (k : ℕ) (T : Finset A), T ⊆ P.space c → (∀ a ∈ T, ¬P.valid c a) →
      (P.space c \ T).card ≤ k * P.minBatch →
      ∃ j ≤ k, ∃ c', P.step^[j] (c, T) = (c', ∅) ∧ P.g c' < P.g c := by
  obtain ⟨a, ha, hav⟩ := hvalid
  intro k
  induction k with
  | zero =>
    intro T _ hT hcard
    have hempty : P.space c \ T = ∅ := Finset.card_eq_zero.1 (by simpa using hcard)
    have : a ∈ T := by
      by_contra hn
      have : a ∈ P.space c \ T := Finset.mem_sdiff.2 ⟨ha, hn⟩
      simp [hempty] at this
    exact absurd hav (hT a this)
  | succ k ih =>
    intro T hsub hT hcard
    have hg0 : P.g c ≠ 0 := hg.ne'
    by_cases hb : ∃ a ∈ P.select c T, P.valid c a
    · refine ⟨1, by omega, P.adopt c (P.select c T), ?_, P.adopt_lt _ _ hb⟩
      simp [step, hg0, hb]
    · -- No valid repair in the batch: it is recorded as verified.
      have hstep : P.step (c, T) = (c, T ∪ P.select c T) := by
        simp [step, hg0, hb]
      have hB := P.select_subset c T
      have hsub' : T ∪ P.select c T ⊆ P.space c :=
        Finset.union_subset hsub (hB.trans Finset.sdiff_subset)
      have hT' : ∀ b ∈ T ∪ P.select c T, ¬P.valid c b := by
        intro b hb'
        rcases Finset.mem_union.1 hb' with h | h
        · exact hT b h
        · exact fun hv => hb ⟨b, h, hv⟩
      have hrest : P.space c \ (T ∪ P.select c T) = (P.space c \ T) \ P.select c T := by
        rw [Finset.sdiff_union_distrib, Finset.sdiff_sdiff_left']
      have hcard' : (P.space c \ (T ∪ P.select c T)).card ≤ k * P.minBatch := by
        rw [hrest, Finset.card_sdiff_of_subset hB]
        have hsel := P.select_card c T
        rw [Nat.succ_mul] at hcard
        rcases le_total P.minBatch (P.space c \ T).card with h | h
        · rw [min_eq_left h] at hsel
          omega
        · rw [min_eq_right h] at hsel
          omega
      obtain ⟨j, hj, c', hrun, hlt⟩ := ih _ hsub' hT' hcard'
      refine ⟨j + 1, by omega, c', ?_, hlt⟩
      rw [Function.iterate_succ_apply, hstep, hrun]

/-- `⌈n / r⌉ r` covers `n`. -/
theorem le_ceilDiv_mul (n r : ℕ) (hr : 1 ≤ r) : n ≤ (n + r - 1) / r * r := by
  have h := Nat.div_add_mod (n + r - 1) r
  have hm := Nat.mod_lt (n + r - 1) (by omega : 0 < r)
  rw [mul_comm] at h
  generalize (n + r - 1) / r * r = p at h ⊢
  omega

/-- **Proposition 2 (b), one configuration.** If the search space of `c` holds a valid repair,
one is adopted within `⌈N_t / minBatch⌉` steps, where `N_t` is the number of candidates. -/
theorem valid_repair_within (c : C) (hg : 0 < P.g c) (hvalid : ∃ a ∈ P.space c, P.valid c a) :
    ∃ j ≤ ((P.space c).card + P.minBatch - 1) / P.minBatch, ∃ c',
      P.step^[j] (c, ∅) = (c', ∅) ∧ P.g c' < P.g c :=
  P.epoch_bound c hg hvalid _ ∅ (Finset.empty_subset _) (by simp)
    (by simpa using le_ceilDiv_mul _ _ P.one_le_minBatch)

/-! ### (b) Completeness -/

/-- **Proposition 2 (b).** If the search space of every configuration with violations holds a
valid repair, the search reaches a configuration that satisfies every specification after finitely
many steps. -/
theorem terminates (hcomplete : ∀ c, 0 < P.g c → ∃ a ∈ P.space c, P.valid c a) (c : C) :
    ∃ n, P.g (P.step^[n] (c, ∅)).1 = 0 := by
  induction h : P.g c using Nat.strong_induction_on generalizing c with
  | _ m ih =>
    rcases Nat.eq_zero_or_pos m with hm | hm
    · exact ⟨0, by simpa [hm] using h⟩
    · obtain ⟨j, -, c', hrun, hlt⟩ := P.valid_repair_within c (h ▸ hm) (hcomplete c (h ▸ hm))
      obtain ⟨n, hn⟩ := ih (P.g c') (h ▸ hlt) c' rfl
      refine ⟨n + j, ?_⟩
      rw [Function.iterate_add_apply, hrun]
      exact hn

/-- **Proposition 2 (b), with a bound.** If moreover every search space holds at most `N`
candidates, the search satisfies every specification within `g(c₁) ⌈N / minBatch⌉` steps. -/
theorem terminates_within (hcomplete : ∀ c, 0 < P.g c → ∃ a ∈ P.space c, P.valid c a) (N : ℕ)
    (hN : ∀ c, (P.space c).card ≤ N) (c : C) :
    ∃ n ≤ P.g c * ((N + P.minBatch - 1) / P.minBatch), P.g (P.step^[n] (c, ∅)).1 = 0 := by
  induction h : P.g c using Nat.strong_induction_on generalizing c with
  | _ m ih =>
    rcases Nat.eq_zero_or_pos m with hm | hm
    · exact ⟨0, by omega, by simpa [hm] using h⟩
    · obtain ⟨j, hj, c', hrun, hlt⟩ := P.valid_repair_within c (h ▸ hm) (hcomplete c (h ▸ hm))
      obtain ⟨n, hn, hzero⟩ := ih (P.g c') (h ▸ hlt) c' rfl
      have hjN : j ≤ (N + P.minBatch - 1) / P.minBatch :=
        hj.trans (Nat.div_le_div_right (by have := hN c; omega))
      refine ⟨n + j, ?_, ?_⟩
      · have : P.g c' + 1 ≤ m := h ▸ hlt
        calc n + j ≤ P.g c' * ((N + P.minBatch - 1) / P.minBatch) + (N + P.minBatch - 1) / P.minBatch := by
              omega
          _ = (P.g c' + 1) * ((N + P.minBatch - 1) / P.minBatch) := by ring
          _ ≤ m * ((N + P.minBatch - 1) / P.minBatch) := Nat.mul_le_mul_right _ this
      · rw [Function.iterate_add_apply, hrun]
        exact hzero

end RepairProcess

end Sonar
