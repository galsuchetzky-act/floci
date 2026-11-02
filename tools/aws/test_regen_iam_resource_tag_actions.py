"""The actions that take aws:ResourceTag/${TagKey}, for the namespaces whose resource tags Floci reads."""
import json
import subprocess

import pytest

import regen_iam_resource_tag_actions as r


def reference(namespace, actions, resources=()):
    return {"Name": namespace, "Actions": list(actions), "Resources": list(resources)}


def every_namespace(*documents):
    given = {document["Name"] for document in documents}
    return list(documents) + [reference(namespace, []) for namespace in r.NAMESPACES if namespace not in given]


def test_an_action_takes_the_key_of_its_resource_type_or_its_own():
    sqs = reference("sqs", [
        {"Name": "SendMessage", "Resources": [{"Name": "queue"}]},
        {"Name": "ListQueues"},
        {"Name": "TagQueue", "ActionConditionKeys": ["aws:ResourceTag/${TagKey}"]},
    ], [{"Name": "queue", "ConditionKeys": ["aws:ResourceTag/${TagKey}"]}])
    assert r.resource_tag_actions(every_namespace(sqs))["sqs"] == ["sqs:SendMessage", "sqs:TagQueue"]


def test_the_keys_an_action_names_for_its_resource_replace_the_types():
    kms = reference("kms", [
        {"Name": "Decrypt", "Resources": [{"Name": "key", "ConditionKeys": []}]},
        {"Name": "Encrypt", "Resources": [{"Name": "key"}]},
    ], [{"Name": "key", "ConditionKeys": ["aws:ResourceTag/${TagKey}"]}])
    assert r.resource_tag_actions(every_namespace(kms))["kms"] == ["kms:Encrypt"]


def test_a_namespace_floci_does_not_read_is_left_out():
    s3 = reference("s3", [{"Name": "GetObject", "ActionConditionKeys": ["aws:ResourceTag/${TagKey}"]}])
    assert "s3" not in r.resource_tag_actions(every_namespace(s3))


def test_a_namespace_missing_upstream_fails():
    documents = [document for document in every_namespace() if document["Name"] != "sns"]
    with pytest.raises(ValueError, match="no document for sns"):
        r.resource_tag_actions(documents)


def test_the_vendored_table_is_well_formed():
    document = json.loads(r.OUTPUT.read_text(encoding="utf-8"))
    assert r.validate(document) == []
    assert list(document["actions"]) == sorted(r.NAMESPACES)
    assert "sqs:SendMessage" in document["actions"]["sqs"]
    assert "sqs:ListQueues" not in document["actions"]["sqs"]


def valid():
    actions = {namespace: [] for namespace in sorted(r.NAMESPACES)}
    for anchor in r.ANCHORS:
        actions[anchor.split(":")[0]].append(anchor)
    return {"actions": actions}


def test_a_valid_table_passes():
    assert r.validate(valid()) == []


@pytest.mark.parametrize("change, problem", [
    (lambda d: d["actions"].pop("ssm"), "ssm is in NAMESPACES but not in the file"),
    (lambda d: d["actions"].__setitem__("s3", ["s3:GetObject"]), "'s3' is not a namespace ResourceTags reads"),
    (lambda d: d["actions"]["sqs"].append("sns:Publish"), "sqs carries 'sns:Publish'"),
    (lambda d: d["actions"]["sqs"].append("sqs:SendMessageBatch"), "sqs:SendMessageBatch is an operation"),
    (lambda d: d["actions"]["sqs"].insert(0, "sqs:TagQueue"), "sqs actions are not sorted"),
    (lambda d: d["actions"]["sqs"].remove("sqs:SendMessage"), "expected sqs:SendMessage to be listed"),
])
def test_a_malformed_table_fails(change, problem):
    document = valid()
    change(document)
    assert any(problem in message for message in r.validate(document))


def test_verify_fails_on_an_invented_action_and_reports_an_added_one():
    untrue, added = r.verify_against({"sqs": ["sqs:ListQueues", "sqs:SendMessage"]},
                                     {"sqs": ["sqs:ReceiveMessage", "sqs:SendMessage"]})
    assert untrue == ["sqs:ListQueues does not take aws:ResourceTag/${TagKey}"]
    assert added == ["sqs:ReceiveMessage"]


@pytest.mark.parametrize("changed", [
    "src/main/resources/aws/iam-resource-tag-actions.json",
    "tools/aws/regen_iam_resource_tag_actions.py",
    "tools/aws/test_regen_iam_resource_tag_actions.py",
    "tools/aws/regen_iam_tag_on_create.py",
    "tools/aws/regen_iam_passed_roles.py",
    "tools/aws/regen_iam_authorized_actions.py",
    "",
])
def test_aggregate_gate_checks_the_table_and_its_imports(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:iam-resource-tag-actions-verify" in result.stdout
    assert result.returncode != 0
    assert "the aws:ResourceTag action table" in result.stdout


@pytest.mark.parametrize("changed", [
    "docs/services/iam.md",
    "src/main/resources/aws/lambda-web-condition-keys.json",
])
def test_aggregate_gate_does_not_check_unrelated_data(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:iam-resource-tag-actions-verify" not in result.stdout
    assert result.returncode == 0


def run_aggregate_gate(tmp_path, changed, fail):
    recursive_make = tmp_path / "check-gate"
    recursive_make.write_text(
        '#!/bin/sh\nprintf "checked:%s\\n" "$2"\n'
        + ('[ "$2" != iam-resource-tag-actions-verify ]\n' if fail else 'exit 0\n'))
    recursive_make.chmod(0o700)
    return subprocess.run(
        ["make", "--no-print-directory", "iam-data-verify", "CHANGED=" + changed,
         "MAKE=" + str(recursive_make)],
        cwd=r.OUTPUT.parents[4], capture_output=True, text=True, check=False)
