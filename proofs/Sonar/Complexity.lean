/-
# The computation of a SONAR step is linear in the number of regions (Proposition 1)

Apart from invoking the decision model and the verifier, a step of SONAR evaluates the Koopman-index
policy (decision rules (6a)–(6c)) and either updates the belief by Bayesian learning (Section 4.5)
or re-initializes it (Section 4.2). This module writes these computations as programs in a cost
monad, `Counted`, that counts one operation per comparison, arithmetic operation, logarithm, power
or rounding (the unit-cost RAM model; indexing and control flow are free). For each program it
proves that it computes exactly the paper's formula and bounds its number of operations:

* `topTwo`: one pass over the `n` beliefs returns a region `i_t` of maximal belief (rule (6a)) and
  the second-largest belief `b̄_t = max_{j ≠ i_t} b_t(j)`, in at most `3n` operations.
* `decide`: the stopping rule (6b) and the number of candidates `r_t` of rule (6c), in at most 11
  operations. `rule_spec` proves that its closed form is the minimum in (6c).
* `update`: the next belief `b_{t+1}(i) ∝ b_t(i) L_t(i)`, in at most `7n + 2 r_t + 2` operations.
* `init`: the initial belief `b(i) ∝ f_1(i) f_2(i)`, `b(∅) = β`, in at most `4n + 1` operations.
* `sonarStep_ops_le`, `sonarInitStep_ops_le`: a whole step takes at most `10 n + 2 r_max + 13`
  operations, so it is `O(n)`.

The scores `f_1`, `f_2`, the decision model's assessment `p_t`, `p_t(∅)` and its probabilities
`m_t(y_k)` of the verified candidates enter the step as inputs, as in Proposition 1.
-/
import Mathlib

open scoped NNReal
open Finset

noncomputable section

namespace Sonar

/-! ### Counting operations -/

/-- A computation that returns a value and the number of operations it performed. -/
structure Counted (α : Type) where
  /-- The value computed. -/
  val : α
  /-- The number of primitive operations performed. -/
  ops : ℕ

namespace Counted

instance : Monad Counted where
  pure a := ⟨a, 0⟩
  bind x f := ⟨(f x.val).val, x.ops + (f x.val).ops⟩

/-- One primitive operation (a comparison, an arithmetic operation, a logarithm, a power or a
rounding) whose result is `a`. -/
def op {α : Type} (a : α) : Counted α := ⟨a, 1⟩

@[simp] theorem pure_val {α : Type} (a : α) : (pure a : Counted α).val = a := rfl
@[simp] theorem pure_ops {α : Type} (a : α) : (pure a : Counted α).ops = 0 := rfl
@[simp] theorem bind_val {α β : Type} (x : Counted α) (f : α → Counted β) :
    (x >>= f).val = (f x.val).val := rfl
@[simp] theorem bind_ops {α β : Type} (x : Counted α) (f : α → Counted β) :
    (x >>= f).ops = x.ops + (f x.val).ops := rfl
@[simp] theorem op_val {α : Type} (a : α) : (op a).val = a := rfl
@[simp] theorem op_ops {α : Type} (a : α) : (op a).ops = 1 := rfl
@[simp] theorem ite_val {α : Type} (c : Prop) [Decidable c] (x y : Counted α) :
    (if c then x else y).val = if c then x.val else y.val := by split <;> rfl
@[simp] theorem ite_ops {α : Type} (c : Prop) [Decidable c] (x y : Counted α) :
    (if c then x else y).ops = if c then x.ops else y.ops := by split <;> rfl

/-- A loop over a list costs at most its length times the most any iteration costs. -/
theorem foldlM_ops_le {α β : Type} (f : β → α → Counted β) (c : ℕ)
    (hf : ∀ s a, (f s a).ops ≤ c) : ∀ (l : List α) (s : β), (l.foldlM f s).ops ≤ c * l.length
  | [], _ => by simp [List.foldlM]
  | a :: l, s => by
    simp only [List.foldlM_cons, bind_ops, List.length_cons]
    have := foldlM_ops_le f c hf l (f s a).val
    have := hf s a
    nlinarith

/-- A loop of single operations computes the fold and costs one operation per element. -/
theorem foldlM_op {α β : Type} (g : β → α → β) :
    ∀ (l : List α) (s : β),
      (l.foldlM (fun s a => op (g s a)) s).val = l.foldl g s ∧
        (l.foldlM (fun s a => op (g s a)) s).ops = l.length
  | [], _ => by simp [List.foldlM]
  | a :: l, s => by
    obtain ⟨h1, h2⟩ := foldlM_op g l (g s a)
    refine ⟨?_, ?_⟩
    · simp only [List.foldlM_cons, bind_val, op_val, List.foldl_cons]
      exact h1
    · simp only [List.foldlM_cons, bind_ops, op_ops, op_val, List.length_cons]
      omega

/-- A loop over the `n` regions that computes each entry independently; its cost is the sum of
the costs of the entries. -/
def tabulate {n : ℕ} {α : Type} (f : Fin n → Counted α) : Counted (Fin n → α) :=
  ⟨fun i => (f i).val, ∑ i, (f i).ops⟩

@[simp] theorem tabulate_val {n : ℕ} {α : Type} (f : Fin n → Counted α) :
    (tabulate f).val = fun i => (f i).val := rfl

theorem tabulate_ops_le {n : ℕ} {α : Type} (f : Fin n → Counted α) (c : ℕ)
    (hf : ∀ i, (f i).ops ≤ c) : (tabulate f).ops ≤ c * n := by
  calc (tabulate f).ops = ∑ i, (f i).ops := rfl
    _ ≤ ∑ _i : Fin n, c := Finset.sum_le_sum fun i _ => hf i
    _ = c * n := by simp [mul_comm]

/-- The sum of `n` values, one addition each. -/
def sum {n : ℕ} (w : Fin n → ℝ≥0) : Counted ℝ≥0 :=
  (List.finRange n).foldlM (fun s i => op (s + w i)) 0

theorem sum_val {n : ℕ} (w : Fin n → ℝ≥0) : (sum w).val = ∑ i, w i := by
  rw [sum, (foldlM_op (fun s i => s + w i) _ 0).1, Fin.sum_univ_def]
  have : ∀ (l : List (Fin n)) (a : ℝ≥0), l.foldl (fun s i => s + w i) a = a + (l.map w).sum := by
    intro l
    induction l with
    | nil => simp
    | cons x l ih => intro a; simp [ih, add_assoc]
  rw [this, zero_add]

theorem sum_ops {n : ℕ} (w : Fin n → ℝ≥0) : (sum w).ops = n := by
  rw [sum, (foldlM_op (fun s i => s + w i) _ 0).2, List.length_finRange]

end Counted

open Counted

namespace Step

variable {n : ℕ}

/-! ### Decision rule (6a): the largest and the second-largest belief -/

/-- One iteration of the scan: keep the best region so far and the largest belief among the
other regions seen so far. -/
def scanStep (b : Fin n → ℝ≥0) (st : Fin n × ℝ≥0) (j : Fin n) : Counted (Fin n × ℝ≥0) := do
  let same ← op (decide (j = st.1))
  if same then pure st
  else
    let gt ← op (decide (b st.1 < b j))
    if gt then pure (j, b st.1)
    else
      let gt2 ← op (decide (st.2 < b j))
      pure (st.1, if gt2 then b j else st.2)

/-- A region of maximal belief `i_t` and the second-largest belief `b̄_t`, by one pass over the
regions. -/
def topTwo [NeZero n] (b : Fin n → ℝ≥0) : Counted (Fin n × ℝ≥0) :=
  (List.finRange n).foldlM (scanStep b) (0, 0)

/-- The scan's invariant over the set `S` of regions seen: `st.1` has the largest belief in `S`
and `st.2` is the largest belief of the other regions in `S` (zero if there are none). -/
def Inv (b : Fin n → ℝ≥0) (S : Finset (Fin n)) (st : Fin n × ℝ≥0) : Prop :=
  st.1 ∈ S ∧ (∀ k ∈ S, b k ≤ b st.1) ∧ st.2 = (S.erase st.1).sup b

theorem inv_scanStep (b : Fin n → ℝ≥0) {S : Finset (Fin n)} {st : Fin n × ℝ≥0}
    (h : Inv b S st) (j : Fin n) : Inv b (insert j S) (scanStep b st j).val := by
  obtain ⟨hmem, hmax, hsec⟩ := h
  by_cases hj : j = st.1
  · simp only [scanStep, hj, decide_true, bind_val, op_val, if_true, pure_val]
    rw [insert_eq_of_mem hmem]
    exact ⟨hmem, hmax, hsec⟩
  by_cases hgt : b st.1 < b j
  · simp only [scanStep, hj, decide_false, bind_val, op_val, Bool.false_eq_true, if_false,
      hgt, decide_true, if_true, pure_val]
    have hjS : j ∉ S := fun hjS => absurd (hmax j hjS) (not_le.2 hgt)
    refine ⟨mem_insert_self j S, ?_, ?_⟩
    · intro k hk
      rcases mem_insert.1 hk with rfl | hk
      · exact le_rfl
      · exact (hmax k hk).trans hgt.le
    · rw [erase_insert hjS]
      exact le_antisymm (le_sup hmem) (Finset.sup_le hmax)
  · simp only [scanStep, hj, decide_false, bind_val, op_val, Bool.false_eq_true, if_false,
      hgt, pure_val]
    refine ⟨mem_insert_of_mem hmem, ?_, ?_⟩
    · intro k hk
      rcases mem_insert.1 hk with rfl | hk
      · exact not_lt.1 hgt
      · exact hmax k hk
    · rw [erase_insert_of_ne hj, sup_insert, ← hsec]
      by_cases h2 : st.2 < b j
      · simp [h2, h2.le]
      · simp [h2, not_lt.1 h2]

theorem inv_foldlM (b : Fin n → ℝ≥0) :
    ∀ (l : List (Fin n)) (S : Finset (Fin n)) (st : Fin n × ℝ≥0), Inv b S st →
      Inv b (S ∪ l.toFinset) (l.foldlM (scanStep b) st).val
  | [], S, st, h => by simpa [List.foldlM] using h
  | j :: l, S, st, h => by
    simp only [List.foldlM_cons, bind_val]
    have := inv_foldlM b l (insert j S) _ (inv_scanStep b h j)
    have hset : S ∪ (j :: l).toFinset = insert j S ∪ l.toFinset := by
      ext k
      simp only [mem_union, mem_insert, List.mem_toFinset, List.mem_cons]
      tauto
    rw [hset]
    exact this

/-- **Rule (6a).** `topTwo` returns a region of maximal belief and the largest belief among the
other regions. -/
theorem topTwo_spec [NeZero n] (b : Fin n → ℝ≥0) :
    (∀ k, b k ≤ b (topTwo b).val.1) ∧ (topTwo b).val.2 = (univ.erase (topTwo b).val.1).sup b := by
  have h0 : Inv b {0} ((0 : Fin n), (0 : ℝ≥0)) := ⟨mem_singleton_self 0, by simp, by simp⟩
  have h := inv_foldlM b (List.finRange n) {0} (0, 0) h0
  have hS : ({0} ∪ (List.finRange n).toFinset : Finset (Fin n)) = univ := by
    ext k; simp
  rw [hS] at h
  exact ⟨fun k => h.2.1 k (mem_univ k), h.2.2⟩

theorem topTwo_ops_le [NeZero n] (b : Fin n → ℝ≥0) : (topTwo b).ops ≤ 3 * n := by
  have hstep : ∀ st j, (scanStep b st j).ops ≤ 3 := by
    intro st j
    simp only [scanStep]
    by_cases hj : j = st.1
    · simp [hj]
    · by_cases hgt : b st.1 < b j <;> simp [hj, hgt]
  simpa [topTwo] using foldlM_ops_le (scanStep b) 3 hstep (List.finRange n) (0, 0)

/-! ### Decision rules (6b)–(6c): whether to stop and the number of candidates to verify -/

/-- Rules (6b)–(6c) in closed form: stop if `b_t(i_t) ≤ 1 / (θ λ g_Φ(c_t))` (an infinite threshold
when `g_Φ(c_t) = 0`); otherwise verify `min {r_max, max {r_min, ⌈(1/θ) ln(b_t(i_t)/b̄_t)⌉}}`
candidates, or `r_max` when `b̄_t = 0`. `rule_spec` proves that this is the minimum in (6c). -/
noncomputable def rule (θ lam : ℝ≥0) (g rmin rmax : ℕ) (bi bbar : ℝ≥0) : ℕ :=
  if g = 0 ∨ (bi : ℝ) ≤ 1 / ((θ : ℝ) * lam * g) then 0
  else if bbar = 0 then rmax
  else min rmax (max (rmin : ℤ) ⌈(1 / (θ : ℝ)) * Real.log ((bi : ℝ) / bbar)⌉).toNat

/-- The computation of rules (6b)–(6c). -/
noncomputable def decide (θ lam : ℝ≥0) (g rmin rmax : ℕ) (bi bbar : ℝ≥0) : Counted ℕ := do
  let s ← op (θ * lam)
  let s ← op (s * g)
  let s ← op (s * bi)
  let stop ← op (Decidable.decide (s ≤ 1))
  if stop then pure 0
  else
    let zero ← op (Decidable.decide (bbar = 0))
    if zero then pure rmax
    else
      let q ← op ((bi : ℝ) / bbar)
      let l ← op (Real.log q)
      let l ← op (l / θ)
      let c ← op ⌈l⌉
      let c ← op (max (rmin : ℤ) c)
      op (min rmax c.toNat)

theorem stop_iff {θ lam : ℝ≥0} (hθ : 0 < θ) (hlam : 0 < lam) (g : ℕ) (bi : ℝ≥0) :
    θ * lam * g * bi ≤ 1 ↔ g = 0 ∨ (bi : ℝ) ≤ 1 / ((θ : ℝ) * lam * g) := by
  rcases Nat.eq_zero_or_pos g with rfl | hg
  · simp
  · have hpos : (0 : ℝ) < (θ : ℝ) * lam * g := by positivity
    rw [← NNReal.coe_le_coe, NNReal.coe_one]
    push_cast
    rw [le_div_iff₀ hpos, mul_comm]
    simp [hg.ne']

/-- **Rules (6b)–(6c).** `decide` computes `rule`. -/
theorem decide_val {θ lam : ℝ≥0} (hθ : 0 < θ) (hlam : 0 < lam) (g rmin rmax : ℕ) (bi bbar : ℝ≥0) :
    (decide θ lam g rmin rmax bi bbar).val = rule θ lam g rmin rmax bi bbar := by
  have hs := stop_iff hθ hlam g bi
  by_cases hstop : θ * lam * g * bi ≤ 1
  · rw [rule, if_pos (hs.1 hstop)]
    simp [decide, hstop]
  · have hns : ¬(g = 0 ∨ (bi : ℝ) ≤ 1 / ((θ : ℝ) * lam * g)) := fun h => hstop (hs.2 h)
    rw [rule, if_neg hns]
    by_cases hz : bbar = 0
    · simp [decide, hstop, hz]
    · simp [decide, hstop, hz, div_eq_inv_mul]

theorem decide_ops_le (θ lam : ℝ≥0) (g rmin rmax : ℕ) (bi bbar : ℝ≥0) :
    (decide θ lam g rmin rmax bi bbar).ops ≤ 11 := by
  simp only [decide, bind_ops, op_ops, op_val, ite_ops, pure_ops]
  split_ifs <;> simp

/-- The set in rule (6c): the `r ∈ {r_min, ..., r_max}` at which the index `I_t(i_t, r)` has dropped
to `max_{j ≠ i_t} I_t(j, 0) = θ b̄_t`. -/
def indexSet (θ : ℝ≥0) (rmin rmax : ℕ) (bi bbar : ℝ≥0) : Set ℕ :=
  {r | rmin ≤ r ∧ r ≤ rmax ∧ (θ : ℝ) * bi * Real.exp (-(θ : ℝ) * r) ≤ (θ : ℝ) * bbar}

/-- **Rule (6c).** When the search does not stop, `rule` is the minimum of the set in (6c), or
`r_max` if the set is empty. -/
theorem rule_spec {θ lam : ℝ≥0} (hθ : 0 < θ) (hlam : 0 < lam) {g rmin rmax : ℕ}
    {bi bbar : ℝ≥0}
    (hgo : ¬(g = 0 ∨ (bi : ℝ) ≤ 1 / ((θ : ℝ) * lam * g))) :
    IsLeast (indexSet θ rmin rmax bi bbar) (rule θ lam g rmin rmax bi bbar) ∨
      (indexSet θ rmin rmax bi bbar = ∅ ∧ rule θ lam g rmin rmax bi bbar = rmax) := by
  have hθ' : (0 : ℝ) < θ := by exact_mod_cast hθ
  have hlam' : (0 : ℝ) < lam := by exact_mod_cast hlam
  have hbi : (0 : ℝ) < bi := by
    rw [not_or, not_le] at hgo
    have hg : (0 : ℝ) < g := by exact_mod_cast Nat.pos_of_ne_zero hgo.1
    exact lt_trans (by positivity) hgo.2
  rw [rule, if_neg hgo]
  by_cases hz : bbar = 0
  · right
    rw [if_pos hz]
    refine ⟨Set.eq_empty_of_forall_notMem fun r hr' => ?_, rfl⟩
    have : (0 : ℝ) < (θ : ℝ) * bi * Real.exp (-(θ : ℝ) * r) := by positivity
    have h : (θ : ℝ) * bi * Real.exp (-(θ : ℝ) * r) ≤ (θ : ℝ) * bbar := hr'.2.2
    rw [hz, NNReal.coe_zero, mul_zero] at h
    linarith
  rw [if_neg hz]
  have hb : (0 : ℝ) < bbar := by exact_mod_cast pos_iff_ne_zero.2 hz
  set t := (1 / (θ : ℝ)) * Real.log ((bi : ℝ) / bbar) with ht
  -- The index condition holds exactly from `⌈t⌉` on.
  have key : ∀ r : ℕ, (θ : ℝ) * bi * Real.exp (-(θ : ℝ) * r) ≤ (θ : ℝ) * bbar ↔ ⌈t⌉ ≤ (r : ℤ) := by
    intro r
    rw [Int.ceil_le, Int.cast_natCast, mul_assoc, mul_le_mul_iff_right₀ hθ', ht, one_div,
      inv_mul_le_iff₀ hθ', Real.log_le_iff_le_exp (by positivity), div_le_iff₀ hb, neg_mul,
      Real.exp_neg, ← div_eq_mul_inv, div_le_iff₀ (Real.exp_pos _), mul_comm (bbar : ℝ)]
  set c := max (rmin : ℤ) ⌈t⌉ with hc
  have hc0 : (c.toNat : ℤ) = c := Int.toNat_of_nonneg (le_max_of_le_left (Int.natCast_nonneg _))
  have hle : ∀ r ∈ indexSet θ rmin rmax bi bbar, c.toNat ≤ r := by
    intro r hr'
    have h1 := (key r).1 hr'.2.2
    have h2 : (rmin : ℤ) ≤ r := by exact_mod_cast hr'.1
    have : c ≤ r := max_le h2 h1
    omega
  by_cases hfit : c.toNat ≤ rmax
  · left
    rw [min_eq_right hfit]
    refine ⟨⟨?_, hfit, (key _).2 ?_⟩, fun r hr' => hle r hr'⟩
    · have : (rmin : ℤ) ≤ c := le_max_left _ _
      omega
    · have : ⌈t⌉ ≤ c := le_max_right _ _
      omega
  · right
    rw [min_eq_left (not_le.1 hfit).le]
    exact ⟨Set.eq_empty_of_forall_notMem fun r hr' => hfit ((hle r hr').trans hr'.2.1), rfl⟩

/-! ### Bayesian learning of the belief -/

/-- The inputs of the belief update after the decision model and the verifier have been invoked. -/
structure Feedback (n : ℕ) where
  /-- `p_t(i)`: the decision model's probability that region `i` contains a valid repair. -/
  p : Fin n → ℝ≥0
  /-- `p_t(∅)`: the decision model's probability that no valid repair exists. -/
  pNone : ℝ≥0
  /-- `m_t(y_1), ..., m_t(y_{r_t})`: the decision model's probabilities of the verified candidates. -/
  m : List ℝ≥0
  /-- `η`: the weight of the decision model's assessment. -/
  η : ℝ

/-- The product `∏_k (1 - m_t(y_k))`. -/
def survival (m : List ℝ≥0) : ℝ≥0 := (m.map fun a => 1 - a).prod

/-- The likelihood `L_t(i)` of region `i` when region `it` (`i_t`) was searched. -/
noncomputable def likelihood (F : Feedback n) (it i : Fin n) : ℝ≥0 :=
  (F.p i / F.pNone) ^ F.η * (if i = it then survival F.m else 1)

/-- The computation of the product `∏_k (1 - m_t(y_k))`, two operations per candidate. -/
def survivalLoop (m : List ℝ≥0) : Counted ℝ≥0 :=
  m.foldlM (fun s a => do let o ← op (1 - a); op (s * o)) 1

theorem survivalLoop_val (m : List ℝ≥0) : (survivalLoop m).val = survival m := by
  have h : ∀ (l : List ℝ≥0) (s : ℝ≥0),
      (l.foldlM (fun s a => do let o ← op (1 - a); op (s * o)) s : Counted ℝ≥0).val =
        s * survival l := by
    intro l
    induction l with
    | nil => simp [List.foldlM, survival]
    | cons a l ih =>
      intro s
      simp only [List.foldlM_cons, bind_val, op_val, ih, survival, List.map_cons, List.prod_cons,
        mul_assoc]
  simpa [survivalLoop] using h m 1

theorem survivalLoop_ops_le (m : List ℝ≥0) : (survivalLoop m).ops ≤ 2 * m.length :=
  foldlM_ops_le _ 2 (fun s a => by simp) m 1

/-- The weight `b_t(i) L_t(i)` of region `i`, given `∏_k (1 - m_t(y_k))` as `om`. -/
def weight (b : Fin n → ℝ≥0) (F : Feedback n) (it : Fin n) (om : ℝ≥0) (i : Fin n) :
    Counted ℝ≥0 := do
  let q ← op (F.p i / F.pNone)
  let q ← op (q ^ F.η)
  let searched ← op (Decidable.decide (i = it))
  let l ← if searched then op (q * om) else pure q
  op (b i * l)

theorem weight_val (b : Fin n → ℝ≥0) (F : Feedback n) (it i : Fin n) :
    (weight b F it (survival F.m) i).val = b i * likelihood F it i := by
  by_cases hi : i = it <;> simp [weight, hi, likelihood]

theorem weight_ops_le (b : Fin n → ℝ≥0) (F : Feedback n) (it : Fin n) (om : ℝ≥0) (i : Fin n) :
    (weight b F it om i).ops ≤ 5 := by
  by_cases hi : i = it <;> simp [weight, hi]

/-- The computation of the belief update: the weights `b_t(i) L_t(i)`, their sum with
`b_t(∅) L_t(∅) = b_t(∅)`, and the normalized belief `(b_{t+1}(∅), b_{t+1})`. -/
def update (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) (it : Fin n) :
    Counted (ℝ≥0 × (Fin n → ℝ≥0)) := do
  let om ← survivalLoop F.m
  let w ← tabulate (weight b F it om)
  let z ← Counted.sum w
  let z ← op (bNone + z)
  let b' ← tabulate fun i => op (w i / z)
  let bNone' ← op (bNone / z)
  pure (bNone', b')

/-- The normalizing constant `b_t(∅) + Σ_i b_t(i) L_t(i)`. -/
noncomputable def normalizer (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) (it : Fin n) :
    ℝ≥0 :=
  bNone + ∑ i, b i * likelihood F it i

/-- **Bayesian learning.** The update computes `b_{t+1}(i) = b_t(i) L_t(i) / Z` and
`b_{t+1}(∅) = b_t(∅) / Z` with `Z` the normalizer, i.e., `b_{t+1}(i) ∝ b_t(i) L_t(i)`. -/
theorem update_val (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) (it : Fin n) :
    (update b bNone F it).val =
      (bNone / normalizer b bNone F it,
        fun i => b i * likelihood F it i / normalizer b bNone F it) := by
  simp only [update, bind_val, op_val, tabulate_val, sum_val, pure_val, survivalLoop_val, weight_val,
    normalizer]

/-- The updated belief is a probability distribution whenever the normalizer is positive. -/
theorem update_sum (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) (it : Fin n)
    (hZ : normalizer b bNone F it ≠ 0) :
    (update b bNone F it).val.1 + ∑ i, (update b bNone F it).val.2 i = 1 := by
  rw [update_val]
  simp only [← Finset.sum_div, ← add_div]
  exact div_self hZ

theorem update_ops_le (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) (it : Fin n) :
    (update b bNone F it).ops ≤ 7 * n + 2 * F.m.length + 2 := by
  have h0 := survivalLoop_ops_le F.m
  have h1 := tabulate_ops_le (weight b F it (survivalLoop F.m).val) 5 (weight_ops_le b F it _)
  have h2 : ∀ (w : Fin n → ℝ≥0) (z : ℝ≥0), (tabulate fun i => op (w i / z)).ops ≤ 1 * n :=
    fun w z => tabulate_ops_le _ 1 fun i => le_of_eq (op_ops _)
  simp only [update, bind_ops, op_ops, pure_ops, sum_ops, op_val]
  have h3 := h2 (tabulate (weight b F it (survivalLoop F.m).val)).val
    ((bNone + (Counted.sum (tabulate (weight b F it (survivalLoop F.m).val)).val).val))
  omega

/-! ### Initializing the belief -/

/-- The computation of the initial belief (Section 4.2) from the scores `f_1`, `f_2` and the prior
`β`; it is also used to re-initialize the belief after a repair is applied. -/
def init (f₁ f₂ : Fin n → ℝ≥0) (β : ℝ≥0) : Counted (ℝ≥0 × (Fin n → ℝ≥0)) := do
  let w ← tabulate fun i => op (f₁ i * f₂ i)
  let z ← Counted.sum w
  let om ← op (1 - β)
  let b ← tabulate fun i => do
    let a ← op (om * w i)
    op (a / z)
  pure (β, b)

/-- **Initial belief.** `b(i) = (1 - β) f_1(i) f_2(i) / Σ_j f_1(j) f_2(j)` and `b(∅) = β`. -/
theorem init_val (f₁ f₂ : Fin n → ℝ≥0) (β : ℝ≥0) :
    (init f₁ f₂ β).val = (β, fun i => (1 - β) * (f₁ i * f₂ i) / ∑ j, f₁ j * f₂ j) := by
  simp [init, sum_val]

theorem init_ops_le (f₁ f₂ : Fin n → ℝ≥0) (β : ℝ≥0) : (init f₁ f₂ β).ops ≤ 4 * n + 1 := by
  have h1 := tabulate_ops_le (fun i => op (f₁ i * f₂ i)) 1 fun i => le_of_eq (op_ops _)
  have h2 : ∀ (om z : ℝ≥0) (w : Fin n → ℝ≥0),
      (tabulate fun i => (op (om * w i) >>= fun a => op (a / z))).ops ≤ 2 * n :=
    fun om z w => tabulate_ops_le _ 2 fun i => by simp
  simp only [init, bind_ops, op_ops, pure_ops, sum_ops, op_val]
  have h3 := h2 (1 - β) (Counted.sum (tabulate fun i => op (f₁ i * f₂ i)).val).val
    (tabulate fun i => op (f₁ i * f₂ i)).val
  omega

/-! ### A step of SONAR -/

/-- The Koopman-index policy `(i_t, r_t) = μ(b_t, c_t)` on the belief `b_t`. -/
noncomputable def policy [NeZero n] (θ lam : ℝ≥0) (g rmin rmax : ℕ) (b : Fin n → ℝ≥0) :
    Counted (Fin n × ℕ) := do
  let top ← topTwo b
  let r ← decide θ lam g rmin rmax (b top.1) top.2
  pure (top.1, r)

theorem policy_ops_le [NeZero n] (θ lam : ℝ≥0) (g rmin rmax : ℕ) (b : Fin n → ℝ≥0) :
    (policy θ lam g rmin rmax b).ops ≤ 3 * n + 11 := by
  simp only [policy, bind_ops, pure_ops]
  have h1 := topTwo_ops_le b
  have h2 := decide_ops_le θ lam g rmin rmax (b (topTwo b).val.1) (topTwo b).val.2
  omega

/-- The policy computes what the paper prescribes: a region of maximal belief (rule (6a)) and
rules (6b)–(6c). -/
theorem policy_val [NeZero n] {θ lam : ℝ≥0} (hθ : 0 < θ) (hlam : 0 < lam) (g rmin rmax : ℕ)
    (b : Fin n → ℝ≥0) :
    let out := (policy θ lam g rmin rmax b).val
    (∀ k, b k ≤ b out.1) ∧
      out.2 = rule θ lam g rmin rmax (b out.1) ((univ.erase out.1).sup b) := by
  obtain ⟨hmax, hsec⟩ := topTwo_spec b
  simp only [policy, bind_val, pure_val]
  exact ⟨hmax, by rw [decide_val hθ hlam, hsec]⟩

/-- The computation of one step of SONAR that updates the belief: evaluate the policy on the belief
`(b_t(∅), b_t)` and apply Bayesian learning with the feedback. -/
noncomputable def sonarStep [NeZero n] (θ lam : ℝ≥0) (g rmin rmax : ℕ) (b : Fin n → ℝ≥0)
    (bNone : ℝ≥0) (F : Feedback n) : Counted ((Fin n × ℕ) × (ℝ≥0 × (Fin n → ℝ≥0))) := do
  let d ← policy θ lam g rmin rmax b
  let b' ← update b bNone F d.1
  pure (d, b')

/-- The computation of one step of SONAR that re-initializes the belief. -/
noncomputable def sonarInitStep [NeZero n] (θ lam : ℝ≥0) (g rmin rmax : ℕ) (b f₁ f₂ : Fin n → ℝ≥0)
    (β : ℝ≥0) : Counted ((Fin n × ℕ) × (ℝ≥0 × (Fin n → ℝ≥0))) := do
  let d ← policy θ lam g rmin rmax b
  let b' ← init f₁ f₂ β
  pure (d, b')

/-- **Proposition 1.** A step that updates the belief takes at most `10 n + 2 r_t + 13` operations,
where `r_t` is the number of verified candidates. -/
theorem sonarStep_ops_le [NeZero n] (θ lam : ℝ≥0) (g rmin rmax : ℕ) (b : Fin n → ℝ≥0)
    (bNone : ℝ≥0) (F : Feedback n) :
    (sonarStep θ lam g rmin rmax b bNone F).ops ≤ 10 * n + 2 * F.m.length + 13 := by
  simp only [sonarStep, bind_ops, pure_ops]
  have h1 := policy_ops_le θ lam g rmin rmax b
  have h2 := update_ops_le b bNone F (policy θ lam g rmin rmax b).val.1
  omega

/-- **Proposition 1.** A step that re-initializes the belief takes at most `7 n + 12` operations. -/
theorem sonarInitStep_ops_le [NeZero n] (θ lam : ℝ≥0) (g rmin rmax : ℕ) (b f₁ f₂ : Fin n → ℝ≥0)
    (β : ℝ≥0) : (sonarInitStep θ lam g rmin rmax b f₁ f₂ β).ops ≤ 7 * n + 12 := by
  simp only [sonarInitStep, bind_ops, pure_ops]
  have h1 := policy_ops_le θ lam g rmin rmax b
  have h2 := init_ops_le f₁ f₂ β
  omega

/-- The step computes what the paper prescribes: the policy and the Bayesian update. -/
theorem sonarStep_val [NeZero n] {θ lam : ℝ≥0} (hθ : 0 < θ) (hlam : 0 < lam) (g rmin rmax : ℕ)
    (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) :
    let out := (sonarStep θ lam g rmin rmax b bNone F).val
    (∀ k, b k ≤ b out.1.1) ∧
      out.1.2 = rule θ lam g rmin rmax (b out.1.1) ((univ.erase out.1.1).sup b) ∧
      out.2 = (bNone / normalizer b bNone F out.1.1,
        fun i => b i * likelihood F out.1.1 i / normalizer b bNone F out.1.1) := by
  obtain ⟨h1, h2⟩ := policy_val hθ hlam g rmin rmax b
  exact ⟨h1, h2, update_val b bNone F _⟩

/-- The re-initializing step computes the policy and the initial belief. -/
theorem sonarInitStep_val [NeZero n] {θ lam : ℝ≥0} (hθ : 0 < θ) (hlam : 0 < lam) (g rmin rmax : ℕ)
    (b f₁ f₂ : Fin n → ℝ≥0) (β : ℝ≥0) :
    let out := (sonarInitStep θ lam g rmin rmax b f₁ f₂ β).val
    (∀ k, b k ≤ b out.1.1) ∧
      out.1.2 = rule θ lam g rmin rmax (b out.1.1) ((univ.erase out.1.1).sup b) ∧
      out.2 = (β, fun i => (1 - β) * (f₁ i * f₂ i) / ∑ j, f₁ j * f₂ j) := by
  obtain ⟨h1, h2⟩ := policy_val hθ hlam g rmin rmax b
  exact ⟨h1, h2, init_val f₁ f₂ β⟩

/-- **Proposition 1 (`O(n)`).** For every `r_max`, there are constants `c` and `d` such that, for
every number of regions `n` and all inputs with at most `r_max` verified candidates, a step of SONAR
that updates the belief takes at most `c n + d` operations. -/
theorem sonarStep_linear (rmax : ℕ) : ∃ c d : ℕ, ∀ (n : ℕ) [NeZero n] (θ lam : ℝ≥0) (g rmin : ℕ)
    (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n), F.m.length ≤ rmax →
      (sonarStep θ lam g rmin rmax b bNone F).ops ≤ c * n + d :=
  ⟨10, 2 * rmax + 13, fun _ _ θ lam g rmin b bNone F hF => by
    have := sonarStep_ops_le θ lam g rmin rmax b bNone F
    omega⟩

/-- **Proposition 1 (`O(n)`).** The same for a step that re-initializes the belief. -/
theorem sonarInitStep_linear : ∃ c d : ℕ, ∀ (n : ℕ) [NeZero n] (θ lam : ℝ≥0) (g rmin rmax : ℕ)
    (b f₁ f₂ : Fin n → ℝ≥0) (β : ℝ≥0),
      (sonarInitStep θ lam g rmin rmax b f₁ f₂ β).ops ≤ c * n + d :=
  ⟨7, 12, fun _ _ θ lam g rmin rmax b f₁ f₂ β => sonarInitStep_ops_le θ lam g rmin rmax b f₁ f₂ β⟩

end Step

end Sonar

end
