#!/usr/bin/env python3
"""Render the deliberation architecture atlas. Standard-library SVG, no remote assets.
Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
"""
from html import escape
from pathlib import Path
import textwrap
import json
import re

HERE = Path(__file__).parent
COLORS = {'data': ('#dae8fc', '#6c8ebf'), 'agent': ('#fff2cc', '#d6b656'),
          'authority': ('#e1d5e7', '#9673a6'), 'runtime': ('#d5e8d4', '#82b366'),
          'external': ('#ffe6cc', '#d79b00'), 'risk': ('#f8cecc', '#b85450')}

class Diagram:
    def __init__(self, title, subtitle):
        self.parts = [f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 1120" role="img" aria-labelledby="title desc" font-family="Helvetica, Arial, sans-serif">
<title id="title">{escape(title)}</title><desc id="desc">{escape(subtitle)}</desc>
<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#3a76b8"/></marker></defs>
<rect width="1920" height="1120" fill="white"/>
<rect width="1920" height="65" fill="#1e5f96"/>
<text x="24" y="30" font-size="23" fill="white" font-weight="bold">{escape(title)}</text>
<text x="24" y="52" font-size="15" fill="#cfe2f3">{escape(subtitle)}</text>''']
    def text(self, x, y, text, size=17, bold=False, color='#333'):
        self.parts.append(f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" font-weight="{"bold" if bold else "normal"}">{escape(text)}</text>')
    def zone(self, x, title, width=600):
        self.parts.append(f'<rect x="{x}" y="84" width="{width}" height="882" rx="8" fill="#fcfcfa" stroke="#b8b8b0"/>')
        self.text(x+18, 113, title, 18, True, '#1e5f96')
    def card(self, x, y, title, body, kind='data', width=552, height=150):
        fill, stroke = COLORS[kind]
        self.parts.append(f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="8" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>')
        self.text(x+16, y+28, title, 19, True)
        lines=[]
        for paragraph in body.split('\n'):
            lines.extend(textwrap.wrap(paragraph, width=int((width-32)/8.6)) or [''])
        if 53 + (len(lines)-1)*23 > height-10:
            raise ValueError((title, 'Text exceeds card', lines))
        for i, line in enumerate(lines): self.text(x+16, y+54+i*23, line, 16)
    def arrow(self, x1, y1, x2, y2, label=''):
        self.parts.append(f'<path d="M{x1},{y1} L{x2},{y2}" fill="none" stroke="#3a76b8" stroke-width="2" marker-end="url(#arrow)"/>')
        if label: self.text((x1+x2)/2+8, (y1+y2)/2-8, label, 14, color='#1e5f96')
    def footer(self, note):
        for i, (name, (fill, stroke)) in enumerate(COLORS.items()):
            x=26+i*215
            self.parts.append(f'<rect x="{x}" y="990" width="22" height="18" fill="{fill}" stroke="{stroke}"/>')
            self.text(x+32,1005,name,15)
        for i,line in enumerate(textwrap.wrap(note, 185)): self.text(26,1046+i*23,line,16)
        self.text(26,1102,'Miniforge • N14 / N15 revision • Ariadne v1.6.1 • Copyright 2025–2026 Christopher Lester • Apache 2.0',13,color='#666')
    def save(self,name):
        (HERE/'diagrams'/name).write_text('\n'.join(self.parts)+ '\n</svg>\n')

def columns(d, headings, cards, flow=True):
    for i,(heading, group) in enumerate(zip(headings,cards)):
        x=24+i*632
        d.zone(x,heading)
        for j,(title,body,kind) in enumerate(group):
            y=140+j*198
            d.card(x+24,y,title,body,kind,height=166)
            if flow and j<len(group)-1:d.arrow(x+300,y+168,x+300,y+194)


d=Diagram('Shared Deliberation — 1. What changes from the original N14',
          'The shared reasoning objective stays. Identity, information flow, and execution authority move to trusted runtime boundaries.')
columns(d,['ORIGINAL CONTRACT / IMPLEMENTATION','REVISED EXPERIMENTAL CORE','ARIADNE TARGET / NOT YET GENERAL'],[
 [('One graph, role-specific activations','Goals, claims, evidence, alternatives and decisions replace transcript relay.','data'),
  ('Roles carry special operations','A synthesizer may adopt decisions; a verifier may record experiments. Role names were trusted in model output.','agent'),
  ('Whole-board context by default','The board is shared; projection and cross-visibility switch shape what an activation sees.','data'),
  ('Prototype loop and promotion gates','In-memory state; no runtime caller. The spec still claimed the component did not exist.','risk')],
 [('Runtime-bound work turns','The runtime stamps participant, responsibility, activation and basis. Closed operation schemas reject forged provenance.','runtime'),
  ('Decisions remain revisable','Adopted decisions retain their record when challenged. Supersession names a replacement; resolved conflicts stay in history.','data'),
  ('Isolated development comparisons','Synthetic fixtures only. Fixed schedules; separate blind boards. No private workspace or global version reaches the model.','runtime'),
  ('Runnable lab with replay','Five development conditions, two tiers, replicates, metering and immutable journal. G0 remains NOT EVALUATED.','runtime')],
 [('Principal and delegation','Use the established acting context and grants. The responsibility is a task assignment, not a credential.','authority'),
  ('Object-level policy and provider gates','Authorize selected labeled inputs before each provider call. Runtime computes output clauses from delivered inputs.','authority'),
  ('Adoption is not permission','A board decision can justify an effect proposal. Only the effect coordinator can authorize, commit and reconcile it.','external'),
  ('Production promotion still gated','General policy enforcement, calibrated long-horizon fixtures and full N15 controls precede adoption.','risk')]],flow=False)
for j in range(4):
    d.arrow(602,223+j*198,678,223+j*198)
    d.arrow(1234,223+j*198,1310,223+j*198)
d.footer('Read left to right for the design delta; read top to bottom within each column. Green is implemented for the synthetic lab; purple describes the Ariadne boundary, not a claim that its complete policy plane already exists.')
d.save('01-design-evolution.svg')

d=Diagram('Shared Deliberation — 2. One work turn and its trust boundaries',
          'Models contribute payloads. The runtime controls identity, admission, authoritative state and receipts.')
columns(d,['INPUT AND ACTIVATION','ATOMIC REASONING COMMIT','EFFECT BOUNDARY'],[
 [('1 · Select participant and allowance','Fixed schedule in the lab; stable participant identity separate from responsibility. Final synthesis is inside the total allowance.','runtime'),
  ('2 · Build the task view','Goals plus relevant objects and prior commit feedback. No raw workspace, sealed score, peer activation metadata or arbitrary tool access.','data'),
  ('3 · Fresh provider invocation','Tool-free CLI in an empty directory. Synthetic-only input. Timeout and observed usage are recorded for every attempt.','agent'),
  ('4 · Untrusted proposal','Operations plus optional synthetic fixture inspections. Unknown fields, evidence classes and authority claims cannot confer permission.','agent')],
 [('5 · Stamp trusted identity','Role mismatch is rejected. Runtime supplies actor, activation and current basis; model identity fields are not authoritative.','runtime'),
  ('6 · Validate the entire candidate','Shape, types, provenance receipts, references, choice points, hard-constraint rules and duplicate payload checks. No partial state escapes.','runtime'),
  ('7 · Commit and derive','One version per atomic transaction. Recompute explicit conflicts. Return committed/rejected feedback to the next turn.','data'),
  ('8 · Persist before continuing','Force immutable hash-chained journal record before the next call. Replay reconstructs the object graph. Report is a derived view.','runtime')],
 [('A decision on the board','A chosen alternative and its rationale, evidence, dissent and unresolved assumptions. This is a reasoning record.','data'),
  ('An exact effect proposal','Canonical arguments, binding, labeled inputs and destination. Never execute by interpreting a decision statement as a command.','external'),
  ('Ariadne decision envelope','Authority, flow and impact checks. Exact approval hash when required; current grant and freshness checks at commit.','authority'),
  ('Effect receipt back to the board','Succeeded, failed or unknown outcome; reconcile with the external system. General effects are outside this synthetic pilot.','external')]])
d.footer('The pilot has no merge, deploy or arbitrary execution capability. Fixture inspection is an allowlisted runtime reader that produces synthetic execution receipts. A model saying that a test passed is only an assertion.')
d.save('02-work-turn-and-effects.svg')

d=Diagram('Shared Deliberation — 3. Scoped information flow',
          'Ariadne §13.4 target: the board is a container. Actual reads create flows; the synthetic lab does not implement the general policy plane.')
for x,title in [(24,'PUBLIC BRANCH A'),(656,'SHARED CONTAINER AND FLOW GATE'),(1288,'RESTRICTED BRANCH B')]: d.zone(x,title)
d.card(48,140,'Public source A','Labeled evidence selected for the public task. No restriction is inherited merely because B exists.',height=135)
d.card(1312,140,'Restricted source B','Clauses retain each imposing authority: tenant, license, purpose and allowed destinations.','authority',height=135)
d.card(48,330,'Eligible provider A','The model call is an egress event. Public-only inputs permit the corresponding provider binding.','agent',height=135)
d.card(1312,330,'Eligible provider B','Resolve retention, residency and training attributes. A provider eligible for A can still be refused for B.','agent',height=135)
d.card(48,570,'Contribution A','Runtime derives its clauses from inputs actually delivered to A. The model cannot erase those clauses.',height=135)
d.card(1312,570,'Contribution B','The derived contribution inherits B restrictions. A summary cannot declassify its source.','authority',height=135)
d.card(680,140,'No implicit cross-branch contamination','Run-wide policy summary is only an audit upper bound. It is not the label applied to every contribution.','data',height=135)
d.card(680,330,'Authorize selected reads and provider egress','The runtime gates object access, purpose and destination before sending inputs. Recheck on later reads and fallback routing.','runtime',height=135)
d.card(680,525,'Shared blackboard stores both contributions','A and B retain distinct labels. Storage placement does not create a flow between their contents.','data',height=205)
d.card(48,780,'A remains public-only','Until A reads B, B does not taint A. This is why selective projections matter to cost and model eligibility.','runtime',height=150)
d.card(680,780,'A later reads B','Authorize that flow first. A later output then inherits the relevant restrictions. Only trusted transforms or scoped approval can relax a flow.','authority',height=150)
d.card(1312,780,'Pilot boundary','The CLI accepts only the two explicitly synthetic task packages. General tenant, rights and destination enforcement remain prerequisites.','risk',height=150)
for x in [324,1588]:
 d.arrow(x,278,x,327)
 d.arrow(x,468,x,567)
d.arrow(680,397,602,397)
d.arrow(1234,397,1308,397)
d.arrow(602,640,678,640)
d.arrow(1310,640,1234,640)
d.arrow(956,522,956,468)
d.footer('Source → eligible provider → contribution → shared storage → authorized later read. Placing B on the board does not disclose it to A. A required constraint that cannot be disclosed requires authorized reformulation or refusal.')
d.save('03-scoped-information-flow.svg')

d=Diagram('Shared Deliberation — 4. Revisable conclusions and scientific work',
          'Structural validation is not a truth oracle. Adoption records a working choice; new evidence may require reconsideration.')
columns(d,['CONSTRUCT THE PROBLEM','TEST AND REVISE','PRESERVE THE DECISION RECORD'],[
 [('Goal + constraints','Task specification supplies the frame. Agents may propose soft constraints; hard constraints need an established spec source.','data'),
  ('Claim + alternative hypothesis','State an assumption or mechanism; link the competing interpretation. Prefer specific statements that can be tested.','agent'),
  ('Actionable challenge','Name the concern and what would resolve it. Existing evidence or an experiment can back it; a useful question is allowed.','agent'),
  ('Bounded experiment proposal','Choose a synthetic evidence fixture in the pilot. General experiments later use governed capsules and runtime receipts.','data')],
 [('Runtime observation','An allowlisted reader emits a receipt. Source class, evidence text and receipt identity originate outside the model.','runtime'),
  ('Update the shared model','Refine a statement with retained history; answer a question; attach evidence; identify explicit contradictions.','data'),
  ('Adopt a scoped decision','One current decision per choice point. Adoption can retain dissent; it never proves every assumption or authorizes an effect.','data'),
  ('Reconsider when evidence changes','Challenge an adopted decision without deleting it. Supersede it explicitly before adopting an incompatible replacement.','agent')],
 [('Accepted record + open dissent','The accepted record stays identifiable; contested status is recorded separately. No history rewrite to make consensus appear unanimous.','data'),
  ('Supersession and conflict history','Replacement references explain why the earlier choice no longer applies. Resolved conflicts remain inspectable.','data'),
  ('Closure outcome','Completed goals, rejected goals, budget boundary, deadlock and stop are distinct. Closure is not automatically task success.','runtime'),
  ('Sealed post-run evaluation','Scoring evaluates final observable choices and consulted evidence. Participants do not receive reference answers or scores.','external')]])
d.footer('The development fixtures test policy design choices, not production artifacts. N15 promotion additionally requires long-horizon tasks, sealed executable acceptance suites, calibration, independent scoring and matched-budget controls.')
d.save('04-evidence-and-reconsideration.svg')

d=Diagram('Shared Deliberation — 5. Experiment topology and promotion',
          'The lab is runnable now. Its controls approximate the questions in N15 without claiming G0 compliance.')
columns(d,['DEVELOPMENT CONDITIONS','ISOLATION AND MEASUREMENT','GATE-QUALITY EXPERIMENT / NEXT'],[
 [('Single participant','One participant repeatedly works on its own typed state. This is not N15 C1 continuous-session conformance.','agent'),
  ('Independent attempts + selection','Fresh private attempts; one metered final selector sees their submitted solutions and rationales.','agent'),
  ('Relay','Private typed state per participant, with prior solutions and rationales passed in sequence. No shared graph.','agent'),
  ('Shared board / blinded board','Same fixed population and activation schedule. Shared condition uses one graph; blind condition uses isolated per-participant graphs.','data')],
 [('Pinned experiment identity','Manifest, prompt and task hashes; exact model name; normalized price weights; tiers and replicate identity.','runtime'),
  ('One total turn allowance','Final synthesis / selection is included. Observed provider usage, normalized cost, timeouts and failed cells are recorded.','runtime'),
  ('No blind side channels','Separate graphs prevent peer status, global versions, validation outcomes and scheduler changes reaching blind participants.','authority'),
  ('Journal → replay → report','Every activation is durable before continuing. Report success counts and paired shared-minus-blind deltas; no significance claim.','data')],
 [('1 · Calibrate conforming tasks','Long-horizon seeded repositories; reference passes and distractors fail sealed tests; baselines neither saturate nor floor.','external'),
  ('2 · Complete controls + budgets','Full N15 C1/C2/C4 and C6/C7 semantics; hard total cost enforcement; provider and context-policy integration.','authority'),
  ('3 · Preregister paired comparison','Pin effect margins, replicate count and uncertainty method. All closing passes consume the same total allowance.','data'),
  ('4 · Evaluate the tested version','Report positive, null, or negative results with identical rigor. A negative result rejects this configuration, not every future shared-state design.','runtime')]])
d.footer('Included development matrix: 2 synthetic tasks × 5 conditions × 2 allowance tiers × 3 replicates = 60 cells, 510 planned activations. Offline mock runs test mechanics only. Every current report says G0: NOT EVALUATED.')
d.save('05-experiments-and-gates.svg')

# Keep the HTML atlas self-contained so file previews need no relative asset loading.
index = HERE / 'index.html'
if index.exists():
    names = ['01-design-evolution.svg','02-work-turn-and-effects.svg',
             '03-scoped-information-flow.svg','04-evidence-and-reconsideration.svg',
             '05-experiments-and-gates.svg']
    tag = '<script id="atlas-data" type="application/json">' + json.dumps([(HERE/'diagrams'/n).read_text() for n in names]) + '</script>'
    html = index.read_text()
    html = re.sub(r'<script id="atlas-data".*?</script>\s*', '', html, flags=re.S)
    html = html.replace('<script>\nconst panels=', tag + '\n<script>\nconst panels=')
    index.write_text(html)
