/-
# Correctness of the reduction from min-sum set cover (Proposition 4)

Proposition 4 states that computing an optimal search policy is NP-hard, by a reduction from
min-sum set cover (MSSC), which is NP-hard [Feige, Lovász, Tetali 2004, Thm. 1]. This module
formalizes the reduction and proves it correct; that MSSC is NP-hard and that the reduction
runs in polynomial time are not formalized (see the README).

An MSSC instance is a family `sets : Fin n → Finset U` that covers `U`. An ordering places set
`i` at position `π i`; element `u` is covered at the first position whose set contains it, and
the cost of the ordering is the sum over `u` of that position plus one, i.e., `|U| A(π)`.

The search instance (`model`) has `g_Φ(c_1) = 1`, `r_max = 1`, one region per set and one possible
cause of the violation per element, equally likely (the prior `b_1(V)` groups the causes by the
set `V` of regions that contain them). A search state is the true cause `u` and the belief: either
repaired, or the set `W` of causes not yet ruled out; the belief determines `g_Φ`, so a policy only
needs the belief. Verifying candidates from region `i` repairs the network if `u ∈ sets i` and
otherwise rules out the causes in `sets i`. The expected cost of a policy is `searchCost / |U|`.

* `exists_policy_le`: every ordering yields a policy whose total cost is at most `1 + κ` times the
  ordering's.
* `mssc_extract_le`: every policy yields an ordering (`extract`, read off the policy's search
  while the network is not repaired) whose cost times `1 + κ` is at most the policy's total cost,
  if `λ ≥ (1 + κ) n`.
* `extract_optimal`: hence the ordering extracted from an optimal search policy is optimal.
-/
import Sonar.Existence

open scoped ENNReal NNReal
open Finset

namespace Sonar

/-! ### The cost of a deterministic search -/

namespace SearchModel

variable {S Info Region : Type*} (M : SearchModel S Info Region Unit)

/-- With a single search outcome of probability one, the cost satisfies the recursion of a
deterministic search: stopping costs `λ g_Φ`, and any other step costs `r + κ` plus what follows. -/
theorem cost_eq_of_deterministic (hp : ∀ s d, M.prob s d () = 1) (μ : Policy Info Region) (s : S) :
    M.cost μ s =
      if M.Stops s (μ (M.info s)) then
        (M.lam : ℝ≥0∞) * M.violated s
      else ((μ (M.info s)).2 : ℝ≥0∞) + M.κ + M.cost μ (M.next s (μ (M.info s)) ()) := by
  have hshift : M.cost μ s = ⨆ T, M.costUpTo μ (T + 1) s := by
    apply le_antisymm
    · refine iSup_le fun T => ?_
      cases T with
      | zero => simp [costUpTo]
      | succ T => exact le_iSup (fun T => M.costUpTo μ (T + 1) s) T
    · exact iSup_le fun T => le_iSup (fun T => M.costUpTo μ T s) (T + 1)
  rw [hshift]
  simp only [costUpTo]
  split_ifs
  · exact iSup_const
  · simp only [Finset.univ_unique, PUnit.default_eq_unit, Finset.sum_singleton, hp, one_mul]
    rw [cost, ENNReal.add_iSup]

end SearchModel

namespace MinSumSetCover

/-- An instance of min-sum set cover: `n` sets that cover the finite set `U`. -/
structure Instance (U : Type*) (n : ℕ) where
  /-- The sets. -/
  sets : Fin n → Finset U
  /-- Every element lies in some set. -/
  covers : ∀ u, ∃ i, u ∈ sets i

/-- An ordering of the sets: set `i` is at position `π i`. -/
abbrev Ordering (n : ℕ) := Fin n ≃ Fin n

/-- The belief of the search: `none` once the network is repaired, otherwise the set of causes
that are not ruled out. -/
abbrev Belief (U : Type*) := Option (Finset U)

namespace Instance

variable {U : Type*} {n : ℕ} (I : Instance U n)

theorem exists_cover (π : Ordering n) (u : U) : ∃ p : ℕ, ∃ i, (π i : ℕ) = p ∧ u ∈ I.sets i :=
  let ⟨i, hi⟩ := I.covers u
  ⟨π i, i, rfl, hi⟩

variable [DecidableEq U]

/-- The first position at which the ordering `π` covers `u` (counting from zero). -/
noncomputable def coverPos (π : Ordering n) (u : U) : ℕ := Nat.find (I.exists_cover π u)

theorem coverPos_le (π : Ordering n) {u : U} {i : Fin n} (hi : u ∈ I.sets i) :
    I.coverPos π u ≤ π i :=
  Nat.find_min' _ ⟨i, rfl, hi⟩

theorem exists_coverPos (π : Ordering n) (u : U) :
    ∃ i, (π i : ℕ) = I.coverPos π u ∧ u ∈ I.sets i :=
  Nat.find_spec (I.exists_cover π u)

theorem coverPos_lt (π : Ordering n) (u : U) : I.coverPos π u < n := by
  obtain ⟨i, hi, -⟩ := I.exists_coverPos π u
  rw [← hi]
  exact (π i).isLt

variable [Fintype U]

/-- The cost of an ordering: the sum over the elements of the position that covers them. -/
noncomputable def mssc (π : Ordering n) : ℕ := ∑ u, (I.coverPos π u + 1)

/-! ### The search instance -/

/-- The state after verifying candidates from region `d.1` (`d.2` of them) when the cause is
`s.1`: the network is repaired if the cause lies in the region's set, and otherwise the set's
causes are ruled out. -/
def step (s : U × Belief U) (d : Fin n × ℕ) : U × Belief U :=
  match s.2 with
  | none => s
  | some W =>
    if d.2 = 0 then s
    else if s.1 ∈ I.sets d.1 then (s.1, none) else (s.1, some (W \ I.sets d.1))

omit [Fintype U] [DecidableEq U] in
/-- The number of violated specifications: one until the network is repaired. -/
def violations (s : U × Belief U) : ℕ :=
  match s.2 with
  | none => 0
  | some _ => 1

/-- The search problem of the reduction, as a model of Section 3 with weights `κ` and `λ`. -/
def model (κ lam : ℝ≥0) : SearchModel (U × Belief U) (Belief U) (Fin n) Unit where
  info := Prod.snd
  violated := violations
  next s d _ := I.step s d
  prob _ _ _ := 1
  lam := lam
  κ := κ
  rmax := 1

/-- The total cost of a policy over the causes; its expected cost is this divided by `|U|`. -/
noncomputable def searchCost (κ lam : ℝ≥0) (μ : Policy (Belief U) (Fin n)) : ℝ≥0∞ :=
  ∑ u, (I.model κ lam).cost μ (u, some univ)

variable {I}

omit [Fintype U] in
theorem cost_eq (κ lam : ℝ≥0) (μ : Policy (Belief U) (Fin n)) (s : U × Belief U) :
    (I.model κ lam).cost μ s =
      if (I.model κ lam).Stops s (μ s.2) then
        (lam : ℝ≥0∞) * violations s
      else ((μ s.2).2 : ℝ≥0∞) + κ + (I.model κ lam).cost μ (I.step s (μ s.2)) :=
  (I.model κ lam).cost_eq_of_deterministic (fun _ _ => rfl) μ s

omit [Fintype U] in
/-- A repaired search stops at no cost. -/
theorem cost_repaired (κ lam : ℝ≥0) (μ : Policy (Belief U) (Fin n)) (u : U) :
    (I.model κ lam).cost μ (u, none) = 0 := by
  rw [cost_eq]
  simp [SearchModel.Stops, model, violations]

/-! ### From an ordering to a policy -/

section OrderingToPolicy

variable [NeZero n] (π : Ordering n)

/-- The regions whose sets meet `W`. -/
def meeting (W : Finset U) : Finset (Fin n) := univ.filter fun i => (I.sets i ∩ W).Nonempty

/-- The policy of ordering `π`: verify one candidate from the first region (in the order `π`)
whose set meets the causes not ruled out, and stop once repaired. -/
noncomputable def policyOf : Policy (Belief U) (Fin n) := fun b =>
  match b with
  | none => (0, 0)
  | some W =>
    if h : (I.meeting W).Nonempty then (π.symm (((I.meeting W).image π).min' (h.image _)), 1)
    else (0, 0)

omit [Fintype U] in
/-- The policy of an ordering verifies at most `r_max = 1` candidate per step. -/
theorem policyOf_admissible (κ lam : ℝ≥0) : (I.model κ lam).Admissible (I.policyOf π) := by
  intro b
  change (I.policyOf π b).2 ≤ 1
  rcases b with _ | W
  · simp [policyOf]
  · by_cases h : (I.meeting W).Nonempty <;> simp [policyOf, h]

omit [Fintype U] in
theorem policyOf_mem {W : Finset U} (h : (I.meeting W).Nonempty) :
    (I.policyOf π (some W)).1 ∈ I.meeting W := by
  simp only [policyOf, dif_pos h]
  obtain ⟨i, hi, hπ⟩ := Finset.mem_image.1 (((I.meeting W).image π).min'_mem (h.image _))
  rw [← hπ, Equiv.symm_apply_apply]
  exact hi

omit [Fintype U] in
theorem policyOf_le {W : Finset U} (h : (I.meeting W).Nonempty) {i : Fin n}
    (hi : i ∈ I.meeting W) : π (I.policyOf π (some W)).1 ≤ π i := by
  simp only [policyOf, dif_pos h, Equiv.apply_symm_apply]
  exact Finset.min'_le _ _ (Finset.mem_image_of_mem π hi)

omit [Fintype U] in
/-- From any belief that still contains the cause `u`, the policy of `π` repairs the network
within one more step than the number of regions before `u`'s cover position that meet the
belief; each step costs `1 + κ`. -/
theorem cost_policyOf_le (κ lam : ℝ≥0) (u : U) :
    ∀ (N : ℕ) (W : Finset U), u ∈ W →
      (univ.filter fun i => (π i : ℕ) < I.coverPos π u ∧ (I.sets i ∩ W).Nonempty).card = N →
      (I.model κ lam).cost (I.policyOf π) (u, some W) ≤ (1 + κ) * (N + 1) := by
  intro N
  induction N using Nat.strong_induction_on with
  | _ N ih =>
    intro W hu hN
    obtain ⟨c, hcπ, hc⟩ := I.exists_coverPos π u
    have hcmeet : c ∈ I.meeting W := by
      simp only [meeting, mem_filter, mem_univ, true_and]
      exact ⟨u, mem_inter.2 ⟨hc, hu⟩⟩
    have hne : (I.meeting W).Nonempty := ⟨c, hcmeet⟩
    set i := (I.policyOf π (some W)).1 with hi_def
    have hr : (I.policyOf π (some W)).2 = 1 := by simp [policyOf, dif_pos hne]
    have hstop : ¬(I.model κ lam).Stops (u, some W) (I.policyOf π (some W)) := by
      simp [SearchModel.Stops, hr, model, violations]
    rw [cost_eq, if_neg hstop, hr]
    simp only [Nat.cast_one]
    change (1 : ℝ≥0∞) + κ + _ ≤ _
    by_cases hui : u ∈ I.sets i
    · have hs : I.step (u, some W) (I.policyOf π (some W)) = (u, none) := by
        simp [step, hr, ← hi_def, hui]
      rw [hs, cost_repaired κ lam _ u, add_zero]
      calc (1 + (κ : ℝ≥0∞)) = (1 + κ) * 1 := (mul_one _).symm
        _ ≤ (1 + κ) * (N + 1) := by gcongr; exact le_add_self
    · have hs : I.step (u, some W) (I.policyOf π (some W)) = (u, some (W \ I.sets i)) := by
        simp [step, hr, ← hi_def, hui]
      rw [hs]
      -- The chosen region comes before `u`'s cover position and leaves the count.
      have hlt : (π i : ℕ) < I.coverPos π u := by
        have hle : π i ≤ π c := I.policyOf_le π hne hcmeet
        rcases (Fin.le_iff_val_le_val.1 hle).lt_or_eq with h | h
        · exact hcπ ▸ h
        · exact absurd (π.injective (Fin.ext h) ▸ hc) hui
      have himeet : i ∈ I.meeting W := I.policyOf_mem π hne
      set A := univ.filter fun j => (π j : ℕ) < I.coverPos π u ∧ (I.sets j ∩ W).Nonempty
      set A' := univ.filter fun j =>
        (π j : ℕ) < I.coverPos π u ∧ (I.sets j ∩ (W \ I.sets i)).Nonempty
      have hsub : A' ⊂ A := by
        refine Finset.ssubset_iff_of_subset ?_ |>.2 ⟨i, ?_, ?_⟩
        · intro j hj
          simp only [A, A', mem_filter, mem_univ, true_and] at hj ⊢
          exact ⟨hj.1, hj.2.mono (inter_subset_inter_left sdiff_subset)⟩
        · simp only [A, mem_filter, mem_univ, true_and]
          simp only [meeting, mem_filter, mem_univ, true_and] at himeet
          exact ⟨hlt, himeet⟩
        · simp [A', inter_sdiff_self]
      have hcard : A'.card < N := hN ▸ Finset.card_lt_card hsub
      calc (1 + (κ : ℝ≥0∞)) + (I.model κ lam).cost (I.policyOf π) (u, some (W \ I.sets i))
          ≤ (1 + κ) + (1 + κ) * (A'.card + 1) := by
            gcongr
            exact ih A'.card hcard _ (mem_sdiff.2 ⟨hu, hui⟩) rfl
        _ = (1 + κ) * ((A'.card + 1) + 1) := by ring
        _ ≤ (1 + κ) * (N + 1) := by
            gcongr
            exact_mod_cast Nat.succ_le_of_lt hcard

/-- **(a)** Every ordering yields a policy whose total cost is at most `1 + κ` times the
ordering's. -/
theorem searchCost_policyOf_le (κ lam : ℝ≥0) :
    I.searchCost κ lam (I.policyOf π) ≤ (1 + κ) * I.mssc π := by
  rw [searchCost, mssc, Nat.cast_sum, Finset.mul_sum]
  refine Finset.sum_le_sum fun u _ => ?_
  have hcard : (univ.filter fun i => (π i : ℕ) < I.coverPos π u ∧
      (I.sets i ∩ univ).Nonempty).card ≤ I.coverPos π u := by
    calc _ ≤ (range (I.coverPos π u)).card := by
          refine Finset.card_le_card_of_injOn (fun i => (π i : ℕ)) (fun i hi => ?_) ?_
          · simp only [coe_filter, Set.mem_setOf_eq, mem_univ, true_and] at hi
            simpa using hi.1
          · intro a _ b _ h
            exact π.injective (Fin.ext h)
      _ = _ := card_range _
  calc (I.model κ lam).cost (I.policyOf π) (u, some univ) ≤ (1 + κ) * (_ + 1) :=
        I.cost_policyOf_le π κ lam u _ univ (mem_univ u) rfl
    _ ≤ (1 + κ) * ((I.coverPos π u : ℝ≥0∞) + 1) := by gcongr
    _ = (1 + κ) * ((I.coverPos π u + 1 : ℕ) : ℝ≥0∞) := by push_cast; rfl

theorem exists_policy_le (κ lam : ℝ≥0) :
    ∃ μ : Policy (Belief U) (Fin n), I.searchCost κ lam μ ≤ (1 + κ) * I.mssc π :=
  ⟨I.policyOf π, I.searchCost_policyOf_le π κ lam⟩

end OrderingToPolicy

/-! ### From a policy to an ordering -/

section PolicyToOrdering

variable (μ : Policy (Belief U) (Fin n))

/-- The causes not ruled out after `k` steps of the search while the network is not repaired. -/
def openCauses : ℕ → Finset U
  | 0 => univ
  | k + 1 =>
    if (μ (some (openCauses k))).2 = 0 then openCauses k
    else openCauses k \ I.sets (μ (some (openCauses k))).1

/-- The region searched at step `k` while the network is not repaired. -/
def region (k : ℕ) : Fin n := (μ (some (I.openCauses μ k))).1

/-- The first of the first `n` steps that searches region `j`, or `n` if none does. -/
noncomputable def firstStep (j : Fin n) : ℕ :=
  by classical exact if h : ∃ k, k < n ∧ I.region μ k = j then Nat.find h else n

theorem region_firstStep {j : Fin n} (h : I.firstStep μ j < n) :
    I.region μ (I.firstStep μ j) = j := by
  classical
  unfold firstStep at h ⊢
  split_ifs at h ⊢ with hex
  · exact (Nat.find_spec hex).2
  · exact absurd h (lt_irrefl n)

theorem firstStep_le {k : ℕ} (hk : k < n) : I.firstStep μ (I.region μ k) ≤ k := by
  classical
  have hex : ∃ m, m < n ∧ I.region μ m = I.region μ k := ⟨k, hk, rfl⟩
  unfold firstStep
  rw [dif_pos hex]
  exact Nat.find_min' hex ⟨hk, rfl⟩

/-- The sort key of the extracted ordering: first step, then index. -/
noncomputable def key (j : Fin n) : ℕ := I.firstStep μ j * n + j

theorem key_injective : Function.Injective (I.key μ) := by
  intro a b h
  unfold key at h
  have ha := a.isLt
  have hb := b.isLt
  have h1 : I.firstStep μ a = I.firstStep μ b := by
    have : ((a : ℕ) + I.firstStep μ a * n) / n = ((b : ℕ) + I.firstStep μ b * n) / n := by
      rw [add_comm (a : ℕ), add_comm (b : ℕ), h]
    rwa [Nat.add_mul_div_right _ _ a.pos, Nat.add_mul_div_right _ _ a.pos, Nat.div_eq_of_lt ha,
      Nat.div_eq_of_lt hb, zero_add, zero_add] at this
  rw [h1] at h
  exact Fin.ext (by omega)

/-- The position of region `j` in the extracted ordering: the number of regions before it. -/
noncomputable def rank (j : Fin n) : ℕ := (univ.filter fun j' => I.key μ j' < I.key μ j).card

theorem rank_lt (j : Fin n) : I.rank μ j < n := by
  calc I.rank μ j < (univ : Finset (Fin n)).card := by
        refine Finset.card_lt_card (Finset.ssubset_iff_of_subset (filter_subset _ _) |>.2 ?_)
        exact ⟨j, mem_univ j, by simp⟩
    _ = n := by simp

theorem rank_injective : Function.Injective (I.rank μ) := by
  intro a b h
  by_contra hne
  rcases lt_or_gt_of_ne ((I.key_injective μ).ne hne) with hlt | hlt
  · have : I.rank μ a < I.rank μ b := by
      refine Finset.card_lt_card (Finset.ssubset_iff_of_subset ?_ |>.2 ⟨a, ?_, ?_⟩)
      · intro x hx
        simp only [mem_filter, mem_univ, true_and] at hx ⊢
        exact hx.trans hlt
      · simpa using hlt
      · simp
    omega
  · have : I.rank μ b < I.rank μ a := by
      refine Finset.card_lt_card (Finset.ssubset_iff_of_subset ?_ |>.2 ⟨b, ?_, ?_⟩)
      · intro x hx
        simp only [mem_filter, mem_univ, true_and] at hx ⊢
        exact hx.trans hlt
      · simpa using hlt
      · simp
    omega

/-- The ordering extracted from a policy: the regions in the order in which the search first
verifies candidates from them while the network is not repaired, then the others. -/
noncomputable def extract : Ordering n :=
  Equiv.ofBijective (fun j => ⟨I.rank μ j, I.rank_lt μ j⟩)
    (Finite.injective_iff_bijective.1 fun _ _ h =>
      I.rank_injective μ (congrArg Fin.val h))

/-- The region searched at step `k < n` is at most at position `k` of the extracted ordering. -/
theorem rank_region_le {k : ℕ} (hk : k < n) : I.rank μ (I.region μ k) ≤ k := by
  set i := I.region μ k
  set m := I.firstStep μ i
  have hm : m ≤ k := I.firstStep_le μ hk
  have hmn : m < n := hm.trans_lt hk
  -- Every region before `i` is first searched before step `m`.
  have hsub : (univ.filter fun j => I.key μ j < I.key μ i) ⊆
      univ.filter fun j => I.firstStep μ j < m := by
    intro j hj
    simp only [mem_filter, mem_univ, true_and, key] at hj ⊢
    have hj' := j.isLt
    have hi' := i.isLt
    by_contra hge
    rw [not_lt] at hge
    rcases hge.lt_or_eq with hlt | heq
    · have : (m + 1) * n ≤ I.firstStep μ j * n := Nat.mul_le_mul_right n hlt
      nlinarith
    · have hjm : I.firstStep μ j < n := heq ▸ hmn
      have hji : j = i := by
        rw [← I.region_firstStep μ hjm, ← I.region_firstStep μ hmn, ← heq]
      rw [hji] at hj
      exact lt_irrefl _ hj
  calc I.rank μ i ≤ (univ.filter fun j => I.firstStep μ j < m).card := card_le_card hsub
    _ ≤ (range m).card := by
        refine Finset.card_le_card_of_injOn (I.firstStep μ) (fun j hj => ?_) ?_
        · simp only [coe_filter, Set.mem_setOf_eq, mem_univ, true_and] at hj
          simpa using hj
        · intro a ha b _ h
          simp only [coe_filter, Set.mem_setOf_eq, mem_univ, true_and] at ha
          have han : I.firstStep μ a < n := ha.trans hmn
          have hbn : I.firstStep μ b < n := h ▸ han
          rw [← I.region_firstStep μ han, ← I.region_firstStep μ hbn, h]
    _ = m := card_range m
    _ ≤ k := hm

/-- Along the search for cause `u`, from step `k` on, `1 + κ` times the position at which the
extracted ordering covers `u` is at most the search's remaining cost plus `(1 + κ) k`, provided that
`λ ≥ (1 + κ) n`. -/
theorem coverPos_le_cost {κ lam : ℝ≥0} (hlam : (1 + κ) * (n : ℝ≥0) ≤ lam) (u : U) :
    ∀ (m k : ℕ), k + m = n → u ∈ I.openCauses μ k →
      (1 + (κ : ℝ≥0∞)) * ((I.coverPos (I.extract μ) u + 1 : ℕ) : ℝ≥0∞) ≤
        (1 + κ) * k + (I.model κ lam).cost μ (u, some (I.openCauses μ k)) := by
  have hpos : (1 + (κ : ℝ≥0∞)) * ((I.coverPos (I.extract μ) u + 1 : ℕ) : ℝ≥0∞) ≤
      (1 + κ) * n := by
    gcongr
    exact_mod_cast I.coverPos_lt (I.extract μ) u
  have hlamE : (1 + (κ : ℝ≥0∞)) * n ≤ lam := by exact_mod_cast hlam
  intro m
  induction m with
  | zero =>
    intro k hk _
    rw [add_zero] at hk
    subst hk
    exact hpos.trans le_self_add
  | succ m ih =>
    intro k hk hu
    have hkn : k < n := by omega
    set W := I.openCauses μ k
    set d := μ (some W)
    rw [cost_eq]
    by_cases hr : d.2 = 0
    · -- The search stops unrepaired and pays `λ ≥ (1 + κ) n`.
      have hstop : (I.model κ lam).Stops (u, some W) (μ (some W)) := Or.inl hr
      rw [if_pos hstop]
      simp only [violations, Nat.cast_one, mul_one]
      exact hpos.trans (hlamE.trans le_add_self)
    · have hstop : ¬(I.model κ lam).Stops (u, some W) (μ (some W)) := by
        simp [SearchModel.Stops, model, violations, hr, d]
      rw [if_neg hstop]
      have hr1 : (1 : ℝ≥0∞) ≤ ((μ (some W)).2 : ℝ≥0∞) := by
        exact_mod_cast Nat.one_le_iff_ne_zero.2 hr
      have hc : 1 + (κ : ℝ≥0∞) ≤ ((μ (some W)).2 : ℝ≥0∞) + κ := by gcongr
      by_cases hui : u ∈ I.sets d.1
      · -- The search repairs the network at step `k`, and the extracted ordering covers `u` by
        -- position `k`.
        have hcov : I.coverPos (I.extract μ) u ≤ k :=
          (I.coverPos_le (I.extract μ) hui).trans (I.rank_region_le μ hkn)
        calc (1 + (κ : ℝ≥0∞)) * ((I.coverPos (I.extract μ) u + 1 : ℕ) : ℝ≥0∞)
            ≤ (1 + κ) * ((k + 1 : ℕ) : ℝ≥0∞) := by
              gcongr
          _ = (1 + κ) * k + (1 + κ) := by push_cast; ring
          _ ≤ (1 + κ) * k + (((μ (some W)).2 : ℝ≥0∞) + κ) := by gcongr
          _ ≤ _ := by exact add_le_add le_rfl le_self_add
      · -- The search rules out the causes of the region and continues from step `k + 1`.
        have hs : I.step (u, some W) (μ (some W)) = (u, some (I.openCauses μ (k + 1))) := by
          simp [step, hr, hui, openCauses, W, d]
        have hu' : u ∈ I.openCauses μ (k + 1) := by
          simp only [openCauses]
          rw [if_neg (show ¬(μ (some (I.openCauses μ k))).2 = 0 from hr)]
          exact mem_sdiff.2 ⟨hu, hui⟩
        rw [hs]
        calc _ ≤ (1 + (κ : ℝ≥0∞)) * ((k + 1 : ℕ) : ℝ≥0∞) +
              (I.model κ lam).cost μ (u, some (I.openCauses μ (k + 1))) :=
              ih (k + 1) (by omega) hu'
          _ = (1 + κ) * k + ((1 + κ) +
              (I.model κ lam).cost μ (u, some (I.openCauses μ (k + 1)))) := by
              push_cast; ring
          _ ≤ _ := by gcongr

/-- **(b)** If `λ ≥ (1 + κ) n`, every policy yields an ordering whose cost times `1 + κ` is at most
the policy's total cost. -/
theorem mssc_extract_le {κ lam : ℝ≥0} (hlam : (1 + κ) * (n : ℝ≥0) ≤ lam) :
    (1 + (κ : ℝ≥0∞)) * (I.mssc (I.extract μ) : ℝ≥0∞) ≤ I.searchCost κ lam μ := by
  rw [mssc, searchCost, Nat.cast_sum, Finset.mul_sum]
  refine Finset.sum_le_sum fun u _ => ?_
  simpa [openCauses] using I.coverPos_le_cost μ hlam u n 0 (zero_add n) (mem_univ u)

end PolicyToOrdering

/-! ### Correctness of the reduction -/

/-- Cancelling the factor `1 + κ`. -/
private theorem mul_le_mul_cancel (κ : ℝ≥0) {a b : ℕ}
    (h : (1 + (κ : ℝ≥0∞)) * a ≤ (1 + κ) * b) : a ≤ b := by
  have h' : ((1 + κ) * a : ℝ≥0) ≤ (1 + κ) * b := by exact_mod_cast h
  have hpos : (0 : ℝ≥0) < 1 + κ := by positivity
  exact_mod_cast (mul_le_mul_iff_right₀ hpos).1 h'

/-- **Proposition 4 (correctness of the reduction).** With `λ ≥ (1 + κ) n`, the ordering extracted
from an optimal search policy of the constructed instance is an optimal ordering for min-sum set
cover. -/
theorem extract_optimal [NeZero n] {κ lam : ℝ≥0} (hlam : (1 + κ) * (n : ℝ≥0) ≤ lam)
    (μstar : Policy (Belief U) (Fin n))
    (hopt : ∀ μ, (I.model κ lam).Admissible μ → I.searchCost κ lam μstar ≤ I.searchCost κ lam μ)
    (π : Ordering n) :
    I.mssc (I.extract μstar) ≤ I.mssc π :=
  mul_le_mul_cancel κ <|
    calc (1 + (κ : ℝ≥0∞)) * (I.mssc (I.extract μstar) : ℝ≥0∞)
        ≤ I.searchCost κ lam μstar := I.mssc_extract_le μstar hlam
      _ ≤ I.searchCost κ lam (I.policyOf π) := hopt _ (I.policyOf_admissible π κ lam)
      _ ≤ (1 + κ) * I.mssc π := I.searchCost_policyOf_le π κ lam

/-- The optimal expected costs coincide: the minimum total search cost is `1 + κ` times the minimum
MSSC cost, so the optimal expected search cost is `(1 + κ) A(π*)`. -/
theorem searchCost_optimal_eq [NeZero n] {κ lam : ℝ≥0} (hlam : (1 + κ) * (n : ℝ≥0) ≤ lam)
    (μstar : Policy (Belief U) (Fin n))
    (hopt : ∀ μ, (I.model κ lam).Admissible μ → I.searchCost κ lam μstar ≤ I.searchCost κ lam μ)
    (πstar : Ordering n) (hπ : ∀ π, I.mssc πstar ≤ I.mssc π) :
    I.searchCost κ lam μstar = (1 + κ) * I.mssc πstar := by
  apply le_antisymm
  · exact (hopt _ (I.policyOf_admissible πstar κ lam)).trans (I.searchCost_policyOf_le πstar κ lam)
  · refine le_trans ?_ (I.mssc_extract_le μstar hlam)
    gcongr
    exact hπ _

end Instance

end MinSumSetCover

end Sonar
