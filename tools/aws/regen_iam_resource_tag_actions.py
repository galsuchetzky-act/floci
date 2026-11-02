#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-resource-tag-actions.json from AWS's Service Reference.

A policy can condition a request on the tags already on the resource it acts on through
`aws:ResourceTag/${TagKey}`. AWS gives an action that key when the Service Reference lists it among the
action's own condition keys, or among the keys of a resource type the action acts on: the resource entry's
own `ConditionKeys` when the action names them, as EC2's do, else the ones the namespace gives that type.

    https://servicereference.us-east-1.amazonaws.com/v1/<service>/<service>.json

`ResourceTags` reads the tags of the resource a request acts on from the service that owns it, chosen by the
`service` segment of the resource's ARN: through its `ResourceTagReader`, or else its `TagHandler`. Only the
namespaces in `NAMESPACES` are read, those whose resources it can read the tags of, so this table lists each
of their actions that takes the key and nothing else. A batch operation is not an action of its own: SQS
`SendMessageBatch` is checked as `sqs:SendMessage` and SNS `PublishBatch` as `sns:Publish`, which are listed.
`IamResourceTagNamespacesIntegrationTest` holds `NAMESPACES` to the readers and tag handlers Floci registers.

The reference is live rather than a pinned dependency, so a fresh generation is not byte-identical. The two
gates split the same way as the other IAM generators:

- `--check` is offline. The file must be well-formed: sorted, every entry `<namespace>:<Action>` of a
  namespace in `NAMESPACES`, every namespace present, and the actions that justify the table listed while
  the batch operation names stay out.
- `--verify` is online and stdlib-only. Every action the file lists must still take the key. An action AWS
  has added is reported without failing: until the next sync it is checked without the key, which is where
  it was before this table existed.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_resource_tag_actions.py           # rewrite the vendored file in place
    python3 tools/aws/regen_iam_resource_tag_actions.py --check   # offline: shape of the file
    python3 tools/aws/regen_iam_resource_tag_actions.py --verify  # online: nothing invented
    python3 tools/aws/regen_iam_resource_tag_actions.py --source-dir <dir>  # read saved <svc>.json files
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from regen_iam_authorized_actions import fetch_references
from regen_iam_passed_roles import NAMESPACE_RE, _shown
from regen_iam_tag_on_create import _condition_keys, _resource_keys

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-resource-tag-actions.json"
RESOURCE_TAG_KEY = "aws:ResourceTag/${TagKey}"

# The namespaces whose resource tags ResourceTags reads: a ResourceTagReader's (dynamodb, iam, kms,
# secretsmanager, sns, ssm) and every TagHandler's service key. S3 and EC2 get the key from
# IamConditionContextResolver instead, as do the CloudWatch Logs actions it covers; ResourceTags keeps those.
NAMESPACES = (
    "airflow", "apigateway", "app-integrations", "appconfig", "appsync", "aps", "aws-marketplace", "backup", "batch",
    "bedrock", "bedrock-agentcore", "codeartifact", "codeguru-reviewer", "connect", "dlm", "dsql", "dynamodb",
    "eks", "emr-serverless", "fis", "guardduty", "iam", "iot", "kafka", "kms", "lambda", "logs", "pipes",
    "resource-explorer-2", "scheduler", "secretsmanager", "securityhub", "sns", "sqs", "ssm",
)

ACTION_RE = re.compile(r"^[A-Za-z][A-Za-z0-9]{0,127}$")
# Actions the table exists for, and batch operations that are checked as one of them rather than as their own.
ANCHORS = ("dynamodb:GetItem", "kms:Decrypt", "secretsmanager:GetSecretValue", "sns:Publish", "sqs:SendMessage")
NOT_ACTIONS = ("dynamodb:TransactGetItems", "sns:PublishBatch", "sqs:SendMessageBatch")


def resource_tag_actions(documents: list[dict]) -> dict[str, list[str]]:
    """Every action of each namespace in NAMESPACES that takes aws:ResourceTag/${TagKey}, sorted."""
    resource_keys = _resource_keys(documents)
    actions: dict[str, list[str]] = {}
    for document in documents:
        namespace = document.get("Name")
        if not isinstance(namespace, str) or not NAMESPACE_RE.match(namespace):
            raise ValueError(f"{namespace!r} is not usable as an IAM namespace")
        if namespace not in NAMESPACES:
            continue
        found = []
        for action in document.get("Actions") or []:
            if not isinstance(action, dict) or not isinstance(action.get("Name"), str):
                raise ValueError(f"{namespace} has an action entry without a 'Name': {action!r}")
            if RESOURCE_TAG_KEY in _condition_keys(namespace, action, resource_keys):
                found.append(f"{namespace}:{action['Name']}")
        actions[namespace] = sorted(found)
    missing = sorted(set(NAMESPACES) - set(actions))
    if missing:
        raise ValueError(f"the Service Reference has no document for {', '.join(missing)}")
    return {namespace: actions[namespace] for namespace in sorted(actions)}


def build(documents: list[dict], provenance: str) -> dict:
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_resource_tag_actions.py",
            "reference": provenance,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-resource-tag-actions-verify checks every entry against AWS.",
        },
        "actions": resource_tag_actions(documents),
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2) + "\n"


def validate(document: dict) -> list[str]:
    """Problems that make the file unusable, rather than merely out of date."""
    namespaces = document.get("actions")
    if not isinstance(namespaces, dict) or not namespaces:
        return ["actions is missing or empty"]
    problems: list[str] = []
    if list(namespaces) != sorted(namespaces):
        problems.append("actions is not sorted by namespace")
    for namespace in sorted(set(NAMESPACES) - set(namespaces)):
        problems.append(f"{namespace} is in NAMESPACES but not in the file")
    listed: set[str] = set()
    for namespace, actions in namespaces.items():
        if namespace not in NAMESPACES:
            problems.append(f"{namespace!r} is not a namespace ResourceTags reads")
            continue
        if not isinstance(actions, list):
            problems.append(f"{namespace} does not hold a list of actions")
            continue
        if actions != sorted(actions):
            problems.append(f"{namespace} actions are not sorted")
        if len(actions) != len(set(actions)):
            problems.append(f"{namespace} lists an action twice")
        for action in actions:
            if not isinstance(action, str) or not action.startswith(namespace + ":") \
                    or not ACTION_RE.match(action[len(namespace) + 1:]):
                problems.append(f"{namespace} carries {action!r}, which is not one of its actions")
                continue
            listed.add(action)
    for anchor in ANCHORS:
        if anchor not in listed:
            problems.append(f"expected {anchor} to be listed")
    for operation in NOT_ACTIONS:
        if operation in listed:
            problems.append(f"{operation} is an operation checked as another action, not an action")
    return problems


def verify_against(vendored: dict[str, list[str]],
                   upstream: dict[str, list[str]]) -> tuple[list[str], list[str]]:
    """What the file claims and AWS does not, and what AWS has added since."""
    untrue: list[str] = []
    added: list[str] = []
    for namespace, actions in vendored.items():
        upstream_actions = set(upstream.get(namespace, []))
        untrue.extend(f"{action} does not take {RESOURCE_TAG_KEY}" for action in actions
                      if action not in upstream_actions)
        added.extend(sorted(upstream_actions - set(actions)))
    return untrue, sorted(added)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="offline: validate the shape of the vendored file")
    parser.add_argument("--verify", action="store_true", help="online: nothing in the vendored file is invented")
    parser.add_argument("--source-dir", type=Path, default=None,
                        help="read saved <service>.json reference files instead of fetching them")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.check or args.verify:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        document = json.loads(args.output.read_text(encoding="utf-8"))
        problems = validate(document)
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        if problems:
            return 1
        entries = sum(len(actions) for actions in document["actions"].values())
        if args.check:
            print(f"{_shown(args.output)} is well-formed ({entries} actions over {len(document['actions'])} "
                  f"namespaces)")
            return 0
        try:
            documents, provenance = fetch_references(args.source_dir)
            upstream = resource_tag_actions(documents)
        except (OSError, ValueError) as e:
            print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
            return 2
        untrue, added = verify_against(document["actions"], upstream)
        for claim in untrue:
            print(f"error: {claim}", file=sys.stderr)
        if untrue:
            print(f"error: {len(untrue)} vendored action(s) do not take the key upstream.", file=sys.stderr)
            return 1
        if added:
            print(f"{_shown(args.output)}: all {entries} actions verified. AWS has since added {len(added)}, so the "
                  f"file is incomplete but not wrong (run without --verify to refresh): "
                  f"{', '.join(added[:10])}{'...' if len(added) > 10 else ''}")
        else:
            print(f"{_shown(args.output)}: all {entries} actions verified against {provenance}, nothing added "
                  f"upstream.")
        return 0

    try:
        documents, provenance = fetch_references(args.source_dir)
        document = build(documents, provenance)
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    problems = validate(document)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    args.output.write_text(render(document), encoding="utf-8")
    entries = sum(len(actions) for actions in document["actions"].values())
    print(f"wrote {_shown(args.output)} ({entries} actions over {len(document['actions'])} namespaces, "
          f"from {provenance})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
