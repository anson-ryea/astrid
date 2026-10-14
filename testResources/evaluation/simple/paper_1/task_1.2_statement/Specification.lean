namespace AstridSpec
def agreement : Prop := ∀ (limit : Nat) (positive : 0 < limit) (value : Nat), Benchmark.target limit positive value ↔ AstridReference.target limit positive value
def compatibility : Prop := ∀ (limit : Nat) (positive : 0 < limit), Benchmark.target limit positive 0
end AstridSpec
