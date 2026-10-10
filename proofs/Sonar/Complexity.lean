/-
# The computation of a SONAR step is linear in the number of regions (Proposition 3)

Apart from invoking the agent and the verifier, a step of SONAR evaluates the Koopman-index
policy (decision rules (5)–(6)) and updates the belief by Bayesian learning (Section 4.5). This
module writes both computations as programs in a cost monad, `Counted`, that counts one
operation per comparison, arithmetic operation, logarithm, power or rounding (the unit-cost RAM
model; indexing and control flow are free). For each program it proves that it computes exactly
the paper's formula and bounds its number of operations:

* `topTwo`: one pass over the `n` beliefs returns a region `x_t` of maximal belief and the
  second-largest belief `b̄_t = max_{j ≠ x_t} b_t(j)` (rule (5)), in at most `3n` operations.
* `decide`: the number of candidates `r_t` of rule (6), in at most 11 operations.
* `update`: the next belief `b_{t+1}(i) ∝ b_t(i) L_t(i)`, in at most `7n + 3` operations.
* `sonarStep_ops_le`: a whole step takes at most `10 n + 14` operations, so it is `O(n)`.

The agent's assessment `p_t`, `p_t(∅)` and the verification feedback `m_t` are the outputs of the
agent and the verifier; they enter the step as inputs, as in the paper's proof.
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

/-! ### Decision rule (5): the largest and the second-largest belief -/

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

/-- A region of maximal belief `x_t` and the second-largest belief `b̄_t`, by one pass over the
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

/-- **Rule (5).** `topTwo` returns a region of maximal belief and the largest belief among the
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

/-! ### Decision rule (6): the number of candidates to verify -/

/-- Rule (6) as stated in the paper: stop if `b_t(x_t) ≤ 1 / (θ λ g_Φ(c_t))` (an infinite
threshold when `g_Φ(c_t) = 0`); otherwise verify `min {r̄_t, max {1, ⌈(1/θ) ln(b_t(x_t)/b̄_t)⌉}}`
candidates, where `ln(b/0) = ∞`. -/
noncomputable def rule (θ lam : ℝ≥0) (g rbar : ℕ) (bx bbar : ℝ≥0) : ℕ :=
  if g = 0 ∨ (bx : ℝ) ≤ 1 / ((θ : ℝ) * lam * g) then 0
  else if bbar = 0 then rbar
  else min rbar (max 1 ⌈(1 / (θ : ℝ)) * Real.log ((bx : ℝ) / bbar)⌉).toNat

/-- The computation of rule (6). -/
noncomputable def decide (θ lam : ℝ≥0) (g rbar : ℕ) (bx bbar : ℝ≥0) : Counted ℕ := do
  let s ← op (θ * lam)
  let s ← op (s * g)
  let s ← op (s * bx)
  let stop ← op (Decidable.decide (s ≤ 1))
  if stop then pure 0
  else
    let zero ← op (Decidable.decide (bbar = 0))
    if zero then pure rbar
    else
      let q ← op ((bx : ℝ) / bbar)
      let l ← op (Real.log q)
      let l ← op (l / θ)
      let c ← op ⌈l⌉
      let c ← op (max 1 c)
      op (min rbar c.toNat)

theorem stop_iff {θ lam : ℝ≥0} (hθ : 0 < θ) (hlam : 0 < lam) (g : ℕ) (bx : ℝ≥0) :
    θ * lam * g * bx ≤ 1 ↔ g = 0 ∨ (bx : ℝ) ≤ 1 / ((θ : ℝ) * lam * g) := by
  rcases Nat.eq_zero_or_pos g with rfl | hg
  · simp
  · have hpos : (0 : ℝ) < (θ : ℝ) * lam * g := by positivity
    rw [← NNReal.coe_le_coe, NNReal.coe_one]
    push_cast
    rw [le_div_iff₀ hpos, mul_comm]
    simp [hg.ne']

/-- **Rule (6).** `decide` computes the number of candidates of rule (6). -/
theorem decide_val {θ lam : ℝ≥0} (hθ : 0 < θ) (hlam : 0 < lam) (g rbar : ℕ) (bx bbar : ℝ≥0) :
    (decide θ lam g rbar bx bbar).val = rule θ lam g rbar bx bbar := by
  have hs := stop_iff hθ hlam g bx
  by_cases hstop : θ * lam * g * bx ≤ 1
  · rw [rule, if_pos (hs.1 hstop)]
    simp [decide, hstop]
  · have hns : ¬(g = 0 ∨ (bx : ℝ) ≤ 1 / ((θ : ℝ) * lam * g)) := fun h => hstop (hs.2 h)
    rw [rule, if_neg hns]
    by_cases hz : bbar = 0
    · simp [decide, hstop, hz]
    · simp [decide, hstop, hz, div_eq_inv_mul]

theorem decide_ops_le (θ lam : ℝ≥0) (g rbar : ℕ) (bx bbar : ℝ≥0) :
    (decide θ lam g rbar bx bbar).ops ≤ 11 := by
  simp only [decide, bind_ops, op_ops, op_val, ite_ops, pure_ops]
  split_ifs <;> simp

/-! ### Bayesian learning of the belief -/

/-- The inputs of the belief update after the agent and the verifier have been invoked: the
agent's assessment `p_t(i)` and `p_t(∅)`, the verification feedback `m_t`, and the weight `η`. -/
structure Feedback (n : ℕ) where
  /-- `p_t(i)`: the agent's probability that region `i` contains a valid repair. -/
  p : Fin n → ℝ≥0
  /-- `p_t(∅)`: the agent's probability that no region contains a valid repair. -/
  pNone : ℝ≥0
  /-- `m_t`: the feedback from verifying the `r_t` candidates of the searched region. -/
  m : ℝ≥0
  /-- `η`: the weight of the agent's assessment. -/
  η : ℝ

/-- The likelihood factor `L_t(i)` of region `i` when region `x` was searched. -/
noncomputable def likelihood (F : Feedback n) (x i : Fin n) : ℝ≥0 :=
  (F.p i / F.pNone) ^ F.η * (if i = x then 1 - F.m else 1)

/-- The weight `b_t(i) L_t(i)` of region `i`, given `1 - m_t` as `om`. -/
def weight (b : Fin n → ℝ≥0) (F : Feedback n) (x : Fin n) (om : ℝ≥0) (i : Fin n) :
    Counted ℝ≥0 := do
  let q ← op (F.p i / F.pNone)
  let q ← op (q ^ F.η)
  let searched ← op (Decidable.decide (i = x))
  let l ← if searched then op (q * om) else pure q
  op (b i * l)

theorem weight_val (b : Fin n → ℝ≥0) (F : Feedback n) (x i : Fin n) :
    (weight b F x (1 - F.m) i).val = b i * likelihood F x i := by
  by_cases hi : i = x <;> simp [weight, hi, likelihood]

theorem weight_ops_le (b : Fin n → ℝ≥0) (F : Feedback n) (x : Fin n) (om : ℝ≥0) (i : Fin n) :
    (weight b F x om i).ops ≤ 5 := by
  by_cases hi : i = x <;> simp [weight, hi]

/-- The computation of the belief update: the weights `b_t(i) L_t(i)`, their sum with
`b_t(∅) L_t(∅) = b_t(∅)`, and the normalized belief `(b_{t+1}(∅), b_{t+1})`. -/
def update (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) (x : Fin n) :
    Counted (ℝ≥0 × (Fin n → ℝ≥0)) := do
  let om ← op (1 - F.m)
  let w ← tabulate (weight b F x om)
  let z ← Counted.sum w
  let z ← op (bNone + z)
  let b' ← tabulate fun i => op (w i / z)
  let bNone' ← op (bNone / z)
  pure (bNone', b')

/-- The normalizing constant `b_t(∅) + Σ_i b_t(i) L_t(i)`. -/
noncomputable def normalizer (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) (x : Fin n) :
    ℝ≥0 :=
  bNone + ∑ i, b i * likelihood F x i

/-- **Bayesian learning.** The update computes `b_{t+1}(i) = b_t(i) L_t(i) / Z` and
`b_{t+1}(∅) = b_t(∅) / Z` with `Z` the normalizer, i.e., `b_{t+1}(i) ∝ b_t(i) L_t(i)`. -/
theorem update_val (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) (x : Fin n) :
    (update b bNone F x).val =
      (bNone / normalizer b bNone F x,
        fun i => b i * likelihood F x i / normalizer b bNone F x) := by
  simp only [update, bind_val, op_val, tabulate_val, sum_val, pure_val, weight_val, normalizer]

/-- The updated belief is a probability distribution whenever the normalizer is positive. -/
theorem update_sum (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) (x : Fin n)
    (hZ : normalizer b bNone F x ≠ 0) :
    (update b bNone F x).val.1 + ∑ i, (update b bNone F x).val.2 i = 1 := by
  rw [update_val]
  simp only [← Finset.sum_div, ← add_div]
  exact div_self hZ

theorem update_ops_le (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) (x : Fin n) :
    (update b bNone F x).ops ≤ 7 * n + 3 := by
  have h1 := tabulate_ops_le (weight b F x (1 - F.m)) 5 (weight_ops_le b F x _)
  have h2 : ∀ (w : Fin n → ℝ≥0) (z : ℝ≥0), (tabulate fun i => op (w i / z)).ops ≤ 1 * n :=
    fun w z => tabulate_ops_le _ 1 fun i => le_of_eq (op_ops _)
  simp only [update, bind_ops, op_ops, pure_ops, sum_ops, op_val]
  have h3 := h2 (tabulate (weight b F x (1 - F.m))).val
    ((bNone + (Counted.sum (tabulate (weight b F x (1 - F.m))).val).val))
  omega

/-! ### A step of SONAR -/

/-- The computation of one step of SONAR apart from the agent and the verifier: evaluate the
Koopman-index policy on the belief `(b_t(∅), b_t)` and update the belief with the feedback. -/
noncomputable def sonarStep [NeZero n] (θ lam : ℝ≥0) (g rbar : ℕ) (b : Fin n → ℝ≥0)
    (bNone : ℝ≥0) (F : Feedback n) : Counted ((Fin n × ℕ) × (ℝ≥0 × (Fin n → ℝ≥0))) := do
  let top ← topTwo b
  let r ← decide θ lam g rbar (b top.1) top.2
  let b' ← update b bNone F top.1
  pure ((top.1, r), b')

/-- **Proposition 3.** A step of SONAR takes at most `10 n + 14` operations, i.e., `O(n)`. -/
theorem sonarStep_ops_le [NeZero n] (θ lam : ℝ≥0) (g rbar : ℕ) (b : Fin n → ℝ≥0)
    (bNone : ℝ≥0) (F : Feedback n) : (sonarStep θ lam g rbar b bNone F).ops ≤ 10 * n + 14 := by
  simp only [sonarStep, bind_ops, pure_ops]
  have h1 := topTwo_ops_le b
  have h2 := decide_ops_le θ lam g rbar (b (topTwo b).val.1) (topTwo b).val.2
  have h3 := update_ops_le b bNone F (topTwo b).val.1
  omega

/-- The step computes what the paper prescribes: a region `x_t` of maximal belief (rule (5)),
the number of candidates of rule (6), and the Bayesian update of the belief. -/
theorem sonarStep_val [NeZero n] {θ lam : ℝ≥0} (hθ : 0 < θ) (hlam : 0 < lam) (g rbar : ℕ)
    (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n) :
    let out := (sonarStep θ lam g rbar b bNone F).val
    (∀ k, b k ≤ b out.1.1) ∧
      out.1.2 = rule θ lam g rbar (b out.1.1) ((univ.erase out.1.1).sup b) ∧
      out.2 = (bNone / normalizer b bNone F out.1.1,
        fun i => b i * likelihood F out.1.1 i / normalizer b bNone F out.1.1) := by
  obtain ⟨hmax, hsec⟩ := topTwo_spec b
  simp only [sonarStep, bind_val, pure_val]
  exact ⟨hmax, by rw [decide_val hθ hlam, hsec], update_val b bNone F _⟩

/-- **Proposition 3 (`O(n)`).** There are constants `c` and `d` such that, for every number of
regions `n` and all inputs, a step of SONAR takes at most `c n + d` operations. -/
theorem sonarStep_linear : ∃ c d : ℕ, ∀ (n : ℕ) [NeZero n] (θ lam : ℝ≥0) (g rbar : ℕ)
    (b : Fin n → ℝ≥0) (bNone : ℝ≥0) (F : Feedback n),
      (sonarStep θ lam g rbar b bNone F).ops ≤ c * n + d :=
  ⟨10, 14, fun _ _ θ lam g rbar b bNone F => sonarStep_ops_le θ lam g rbar b bNone F⟩

end Step

end Sonar

end
