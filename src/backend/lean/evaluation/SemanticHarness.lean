import Lean

namespace AstridHarness

open Lean Elab Command

/-- Inspect parsed syntax rather than searching comments or quoted strings. -/
partial def inspect (stx : Syntax) (forbidden : Array String) (privateProof := false) : CommandElabM Unit := do
  match stx with
  | .ident _ _ name _ =>
    let text := name.toString
    if !privateProof && (text.splitOn "." |>.any (fun part => part.startsWith "Astrid")) then
      throwError "reserved evaluator identifier"
    if forbidden.any (fun value => text == value || text.endsWith ("." ++ value) ||
        text == (value.splitOn ".").getLast!) then
      throwError "forbidden identifier: {text}"
    if #["sorry", "admit", "run_tac", "run_cmd", "run_elab", "run_meta", "native_decide", "bv_decide"].contains text then
      throwError "forbidden mechanism: {text}"
  | .atom _ text =>
    if #["sorry", "admit", "unsafe", "axiom", "opaque", "initialize", "builtin_initialize",
        "elab", "macro", "run_tac", "run_cmd", "run_elab", "run_meta", "native_decide", "bv_decide"].contains text then
      throwError "forbidden mechanism: {text}"
  | .node _ kind args =>
    if kind.toString == "Lean.Parser.Term.attributes" then
      throwError "submission attributes are not supported"
    for child in args do inspect child forbidden privateProof
  | .missing => pure ()

def validate (source : String) (block : Bool) (helpers : Bool)
    (outputs forbidden : Array String) : CommandElabM Unit := do
  let env ← getEnv
  if !block then
    match Parser.runParserCategory env `term source with
    | .error error => throwError "invalid term: {error}"
    | .ok stx => inspect stx forbidden
  else
    let input := Parser.mkInputContext source "submission"
    let mut state : Parser.ModuleParserState := {}
    let mut count := 0
    repeat
      let (stx, next, messages) := Parser.parseCommand input { env := env, options := {} } state {}
      if messages.hasErrors then throwError "invalid declaration syntax"
      if Parser.isTerminalCommand stx then break
      unless stx.getKind == ``Lean.Parser.Command.declaration do
        throwError "only declarations are permitted in the submission slot"
      inspect stx forbidden
      let name := stx[1][1][0].getId.toString
      if name.contains '.' || name.contains '|' || name.contains '\n' || name.contains '\r' || name.startsWith "_" then
        throwError "declarations must have an unqualified name inside Benchmark"
      if !helpers && !outputs.contains ("Benchmark." ++ name) then
        throwError "local helper declarations are not permitted"
      count := count + 1
      state := next
    if count == 0 then throwError "empty declaration block"

/-- A private script is one proof term, never a replacement command or statement. -/
def validateProof (source : String) : CommandElabM Unit := do
  match Parser.runParserCategory (← getEnv) `term source with
  | .error error => throwError "invalid private proof body: {error}"
  | .ok stx => inspect stx #[] true

/-- Audit the actual environment, including inductive constructors and generated helpers. -/
def auditCandidate (outputs : Array String) : CommandElabM Unit := do
  let env ← getEnv
  for output in outputs do
    unless env.contains (output.toName) do throwError "missing required output: {output}"
  for (name, _) in env.constants.toList do
    if (name.toString.splitOn "Benchmark.").length > 1 then
      let axioms ← collectAxioms name
      logInfo m!"ASTRID_AUDIT|{name}|{String.intercalate "," (axioms.toList.map toString)}"
  logInfo "ASTRID_AUDIT_END"

def auditCertificate (name : Name) : CommandElabM Unit := do
  unless (← getEnv).contains name do throwError "missing certificate"
  let axioms ← collectAxioms name
  logInfo m!"ASTRID_CERTIFICATE|{name}|{String.intercalate "," (axioms.toList.map toString)}"

end AstridHarness
