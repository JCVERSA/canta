#!/usr/bin/env python3
"""Print a per-class and total summary of the JVM unit test results.

Called by ci.yml after `testDebugUnitTest`, piped into build.log so the counts
also end up in the log published to .ci-logs/ when a job fails.

It exists as a file rather than an inline heredoc because a heredoc inside a
YAML block scalar is a trap: YAML strips the block's common indentation, so a
terminator that looks flush-left in the editor still arrives at bash with
leading whitespace and the shell reports "here-document delimited by end of
file". That is what broke the first version of this step.

Exit code is 0 even with no reports (a build that failed before compiling has
nothing to summarise); the test task's own exit code is what gates the job.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

RESULTS = "app/build/test-results/testDebugUnitTest/*.xml"


def main() -> int:
    total = failures = errors = skipped = 0
    classes = 0
    for path in sorted(glob.glob(RESULTS)):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f"unreadable test report {os.path.basename(path)}: {exc}")
            continue
        cases = int(root.get("tests", 0))
        failed = int(root.get("failures", 0))
        broken = int(root.get("errors", 0))
        ignored = int(root.get("skipped", 0))
        print(f"{root.get('name', os.path.basename(path)):58} tests={cases} failures={failed} errors={broken}")
        total += cases
        failures += failed
        errors += broken
        skipped += ignored
        classes += 1

    if classes == 0:
        print("no unit test reports found (the test task did not run)")
        return 0

    print(f"TOTAL classes={classes} tests={total} failures={failures} errors={errors} skipped={skipped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
