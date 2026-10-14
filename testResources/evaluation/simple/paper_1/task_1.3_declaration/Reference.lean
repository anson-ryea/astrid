namespace AstridReference
inductive Target (n : Nat) : Prop where
  | positive (positive : 0 < n) : Target n
end AstridReference
