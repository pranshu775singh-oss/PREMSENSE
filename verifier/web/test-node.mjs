// Tries the browser checker (index.html) against files made by the real exporter and the real
// probe format, without a browser: node verifier/web/test-node.mjs <evidence.json> [intended_log.jsonl]
//
// Exit status 0 only if untouched files are accepted AND every changed byte is rejected.
import fs from "node:fs";
import vm from "node:vm";
import { webcrypto } from "node:crypto";

const [evidencePath, intendedPath] = process.argv.slice(2);
if (!evidencePath) {
  console.error("usage: node test-node.mjs <evidence.json> [intended_log.jsonl]");
  process.exit(64);
}

const html = fs.readFileSync(new URL("./index.html", import.meta.url), "utf8");
const script = html.split("<script>")[1].split("</script>")[0];

// The page wires itself to the DOM when it loads; give it something harmless to wire to.
const element = () =>
  new Proxy(function () {}, {
    get: (_t, p) => (p === "addEventListener" || p === "append" ? () => {} : p === "classList" ? { add() {}, remove() {} } : p === "style" ? {} : element()),
    set: () => true,
    apply: () => element(),
  });
const document = { getElementById: () => element(), createElement: () => element() };
const window = {};

const context = vm.createContext({
  window, document, crypto: webcrypto, TextEncoder, TextDecoder, atob, btoa,
  Uint8Array, Math, Set, Object, Array, Number, String, JSON, Date, console, Error,
});
vm.runInContext(script, context);
const { verifyEvidence, verifyIntendedLog } = window.PermSenseVerify;

let failures = 0;
const fail = (message) => { failures++; console.error("FAIL: " + message); };

async function exercise(name, path, verify, stride) {
  const original = new Uint8Array(fs.readFileSync(path));

  const good = await verify(original);
  if (!good.accepted) fail(`${name}: the untouched file was rejected: ${JSON.stringify(good.checks.filter((c) => !c.ok))}`);
  else console.log(`ok   ${name}: untouched file accepted (${original.length} bytes, ${good.checks.length} checks)`);

  let tried = 0, caught = 0;
  for (let i = 0; i < original.length; i += stride) {
    const copy = original.slice();
    copy[i] ^= 1;
    tried++;
    const result = await verify(copy);
    if (!result.accepted) caught++;
    else fail(`${name}: a change at byte ${i} of ${original.length} was NOT caught`);
  }
  console.log(`${caught === tried ? "ok  " : "FAIL"} ${name}: ${caught} of ${tried} single-bit changes rejected`);
}

// STRIDE=1 changes every byte (slow: a few thousand signature checks); the default samples every 7th.
const stride = Number(process.env.STRIDE || 7);
await exercise("evidence report", evidencePath, verifyEvidence, stride);
if (intendedPath) await exercise("probe log", intendedPath, verifyIntendedLog, Math.max(1, Math.floor(stride / 3)));

// A report cut short, or with something appended, is not the report that was signed.
const bytes = new Uint8Array(fs.readFileSync(evidencePath));
if ((await verifyEvidence(bytes.slice(0, bytes.length - 5))).accepted) fail("a truncated report was accepted");
const longer = new Uint8Array(bytes.length + 1); longer.set(bytes); longer[bytes.length] = 0x0a;
if ((await verifyEvidence(longer)).accepted) fail("a report with a line appended was accepted");
for (const junk of ["", "\n", "not json\n", "{}\n{}\n"]) {
  if ((await verifyEvidence(new TextEncoder().encode(junk))).accepted) fail(`junk ${JSON.stringify(junk)} was accepted`);
}

if (failures) { console.error(`${failures} failure(s)`); process.exit(1); }
console.log("all good");
