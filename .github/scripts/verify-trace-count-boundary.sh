#!/usr/bin/env bash
set -euo pipefail
codec=src/main/java/works/nuty/codon/network/NetworkCodecs.java
test_source=src/test/java/works/nuty/codon/network/NetworkCodecsTest.java
report=build/test-results/test/TEST-works.nuty.codon.network.NetworkCodecsTest.xml
evidence=build/trace-count-evidence
backup=$(mktemp -d)
cp "$codec" "$backup/NetworkCodecs.java"
cp "$test_source" "$backup/NetworkCodecsTest.java"
trap 'cp "$backup/NetworkCodecs.java" "$codec"; cp "$backup/NetworkCodecsTest.java" "$test_source"' EXIT
mkdir -p "$evidence"
cp "$report" "$evidence/baseline.xml"
python3 - <<'PY'
from pathlib import Path
p = Path('src/main/java/works/nuty/codon/network/NetworkCodecs.java')
s = p.read_text()
needle = '.apply(ByteBufCodecs.list(ExecutionFlowHistory.MAX_TRACES));'
assert s.count(needle) == 1
p.write_text(s.replace(needle, '.apply(ByteBufCodecs.list());'))
PY
git diff -- "$codec" > "$evidence/mutation.patch"
git fetch --no-tags --depth=1 origin 5dc7d916e39875eedf2ffde134862b553070b054
git show 5dc7d916e39875eedf2ffde134862b553070b054:"$test_source" > "$test_source"
./gradlew --no-daemon test --tests '*NetworkCodecsTest' > "$evidence/old-tests-mutated.log" 2>&1
cp "$report" "$evidence/old-tests-mutated.xml"
cp "$backup/NetworkCodecsTest.java" "$test_source"
set +e
./gradlew --no-daemon test --tests '*NetworkCodecsTest' > "$evidence/new-tests-mutated.log" 2>&1
mutation_exit=$?
set -e
cp "$report" "$evidence/new-tests-mutated.xml"
if [[ "$mutation_exit" != 1 ]]; then
  echo "Expected Gradle test failure exit 1, received $mutation_exit"
  exit 1
fi
python3 - <<'PY'
from pathlib import Path
import json
import xml.etree.ElementTree as ET
root = Path('build/trace-count-evidence')
summary = []
for case, expected_failures in [('baseline', 0), ('old-tests-mutated', 0), ('new-tests-mutated', 1)]:
    suite = ET.parse(root / (case + '.xml')).getroot()
    counts = {k: int(suite.attrib.get(k, 0)) for k in ['tests', 'failures', 'errors', 'skipped']}
    assert counts == {'tests': 6, 'failures': expected_failures, 'errors': 0, 'skipped': 0}, (case, counts)
    failures = [t for t in suite.findall('testcase') if t.find('failure') is not None]
    if failures:
        assert failures[0].attrib['name'] == 'pauseDecoderAcceptsTheTraceLimitAndRejectsOneExtraCompleteTrace()'
        message = failures[0].find('failure').attrib['message']
        assert 'DecoderException' in message and 'but nothing was thrown' in message, message
    result = {'case': case, **counts}
    summary.append(result)
    print(json.dumps(result))
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
PY
