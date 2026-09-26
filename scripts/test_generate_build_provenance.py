#!/usr/bin/env python3
"""Unit tests for generate-build-provenance.py (stdlib-only)."""

import contextlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest

_MODULE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "generate-build-provenance.py")
_SPEC = importlib.util.spec_from_file_location("generate_build_provenance", _MODULE_PATH)
gbp = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gbp)  # type: ignore[union-attr]


def _init_git_repo(repo_dir: str) -> str:
    """Initialize a throwaway git repo with one commit; return its commit sha."""
    env = dict(os.environ)
    env.update(
        {
            "GIT_AUTHOR_NAME": "Test",
            "GIT_AUTHOR_EMAIL": "test@example.com",
            "GIT_COMMITTER_NAME": "Test",
            "GIT_COMMITTER_EMAIL": "test@example.com",
        }
    )
    subprocess.run(["git", "init", "-q"], cwd=repo_dir, check=True, env=env)
    readme = os.path.join(repo_dir, "README.md")
    with open(readme, "w", encoding="utf-8") as fh:
        fh.write("hello\n")
    subprocess.run(["git", "add", "."], cwd=repo_dir, check=True, env=env)
    subprocess.run(
        ["git", "commit", "-q", "-m", "initial"],
        cwd=repo_dir,
        check=True,
        env=env,
    )
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo_dir,
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    )
    return result.stdout.strip()


class SplitGavTests(unittest.TestCase):
    def test_valid_gav(self):
        self.assertEqual(
            gbp.split_gav("com.snowflake:snowpark_2.12:4.3.4"),
            gbp.Gav("com.snowflake", "snowpark_2.12", "4.3.4"),
        )

    def test_rejects_wrong_segment_count(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.split_gav("com.snowflake:snowpark_2.12")

    def test_rejects_empty_segment(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.split_gav("net.snowflake::4.3.4")

    def test_rejects_extra_segment(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.split_gav("com.snowflake:snowpark_2.12:4.3.4:extra")


class ComputeSha256Tests(unittest.TestCase):
    def test_matches_known_digest(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "artifact.jar")
            with open(path, "wb") as fh:
                fh.write(b"hello world")
            # sha256("hello world")
            expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"  # pragma: allowlist secret
            self.assertEqual(gbp.compute_sha256(path), expected)

    def test_missing_file_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(gbp.ProvenanceError):
                gbp.compute_sha256(os.path.join(tmp, "does-not-exist.jar"))

    def test_directory_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(gbp.ProvenanceError):
                gbp.compute_sha256(tmp)


class ValidateRefTests(unittest.TestCase):
    def test_accepts_matching_release_tag(self):
        gbp.validate_ref("v4.3.4", "4.3.4", allow_non_release_ref=False)

    def test_rejects_non_release_ref_by_default(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.validate_ref("main", "4.3.4", allow_non_release_ref=False)

    def test_rejects_branch_like_ref(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.validate_ref("gshe/poc-branch", "4.3.4", allow_non_release_ref=False)

    def test_rejects_version_prefix_tag(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.validate_ref("v4.3.4-rc1", "4.3.4-rc1", allow_non_release_ref=False)

    def test_rejects_version_mismatch(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.validate_ref("v4.3.4", "4.3.5", allow_non_release_ref=False)

    def test_rejects_empty_ref(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.validate_ref("", "4.3.4", allow_non_release_ref=False)

    def test_allow_non_release_ref_bypasses_tag_check(self):
        gbp.validate_ref("gshe/poc-branch", "4.3.4-sproc-poc.abc123", allow_non_release_ref=True)

    def test_allow_non_release_ref_bypasses_version_mismatch(self):
        gbp.validate_ref("v4.3.4", "9.9.9", allow_non_release_ref=True)

    def test_allow_non_release_ref_still_rejects_empty_ref(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.validate_ref("", "4.3.4", allow_non_release_ref=True)


class ValidateBuildIdTests(unittest.TestCase):
    def test_strips_whitespace(self):
        self.assertEqual(gbp.validate_build_id("  1234  "), "1234")

    def test_rejects_empty(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.validate_build_id("")

    def test_rejects_whitespace_only(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.validate_build_id("   ")


class ResolveGitCommitTests(unittest.TestCase):
    def test_resolves_head_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            expected_commit = _init_git_repo(tmp)
            self.assertEqual(gbp.resolve_git_commit(tmp), expected_commit)

    def test_non_git_directory_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(gbp.ProvenanceError):
                gbp.resolve_git_commit(tmp)


class BuildProvenanceRecordTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo_root = self.tmp.name
        self.commit = _init_git_repo(self.repo_root)
        self.artifact_path = os.path.join(self.repo_root, "snowpark_2.12.jar")
        with open(self.artifact_path, "wb") as fh:
            fh.write(b"fake jar bytes")
        self.expected_sha256 = gbp.compute_sha256(self.artifact_path)

    def test_happy_path_release_build(self):
        record = gbp.build_provenance_record(
            artifact_path=self.artifact_path,
            gav="com.snowflake:snowpark_2.12:4.3.4",
            ref="v4.3.4",
            build_id="12345",
            repository="snowflakedb/snowpark-java-scala",
            artifact_type="jar",
            repo_root=self.repo_root,
            allow_non_release_ref=False,
        )
        self.assertEqual(
            record,
            {
                "schemaVersion": 1,
                "artifactType": "jar",
                "gav": "com.snowflake:snowpark_2.12:4.3.4",
                "sha256": self.expected_sha256,
                "source": {
                    "repository": "snowflakedb/snowpark-java-scala",
                    "commit": self.commit,
                    "ref": "v4.3.4",
                },
                "build": {"id": "12345"},
            },
        )

    def test_rejects_branch_ref_without_flag(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.build_provenance_record(
                artifact_path=self.artifact_path,
                gav="com.snowflake:snowpark_2.12:4.3.4-sproc-poc.abc123",
                ref="gshe/poc-java-sproc-connection-factory",
                build_id="12345",
                repository="snowflakedb/snowpark-java-scala",
                artifact_type="jar",
                repo_root=self.repo_root,
                allow_non_release_ref=False,
            )

    def test_rejects_gav_version_tag_mismatch(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.build_provenance_record(
                artifact_path=self.artifact_path,
                gav="com.snowflake:snowpark_2.12:4.3.5",
                ref="v4.3.4",
                build_id="12345",
                repository="snowflakedb/snowpark-java-scala",
                artifact_type="jar",
                repo_root=self.repo_root,
                allow_non_release_ref=False,
            )

    def test_rejects_malformed_gav(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.build_provenance_record(
                artifact_path=self.artifact_path,
                gav="not-a-gav",
                ref="v4.3.4",
                build_id="12345",
                repository="snowflakedb/snowpark-java-scala",
                artifact_type="jar",
                repo_root=self.repo_root,
                allow_non_release_ref=False,
            )

    def test_rejects_missing_artifact(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.build_provenance_record(
                artifact_path=os.path.join(self.repo_root, "missing.jar"),
                gav="com.snowflake:snowpark_2.12:4.3.4",
                ref="v4.3.4",
                build_id="12345",
                repository="snowflakedb/snowpark-java-scala",
                artifact_type="jar",
                repo_root=self.repo_root,
                allow_non_release_ref=False,
            )

    def test_rejects_empty_build_id(self):
        with self.assertRaises(gbp.ProvenanceError):
            gbp.build_provenance_record(
                artifact_path=self.artifact_path,
                gav="com.snowflake:snowpark_2.12:4.3.4",
                ref="v4.3.4",
                build_id="   ",
                repository="snowflakedb/snowpark-java-scala",
                artifact_type="jar",
                repo_root=self.repo_root,
                allow_non_release_ref=False,
            )

    def test_poc_mode_allows_branch_and_synthetic_version(self):
        record = gbp.build_provenance_record(
            artifact_path=self.artifact_path,
            gav="com.snowflake:snowpark_2.12:4.3.4-sproc-poc.abc123",
            ref="gshe/poc-java-sproc-connection-factory",
            build_id="12345",
            repository="snowflakedb/snowpark-java-scala",
            artifact_type="jar",
            repo_root=self.repo_root,
            allow_non_release_ref=True,
        )
        self.assertEqual(record["source"]["ref"], "gshe/poc-java-sproc-connection-factory")
        self.assertEqual(record["gav"], "com.snowflake:snowpark_2.12:4.3.4-sproc-poc.abc123")

    def test_sha256_reflects_actual_bytes_not_input(self):
        # Even if a caller lies in --gav/--ref, the hash always reflects the
        # real artifact bytes, never a caller-supplied value.
        record = gbp.build_provenance_record(
            artifact_path=self.artifact_path,
            gav="com.snowflake:snowpark_2.12:4.3.4",
            ref="v4.3.4",
            build_id="12345",
            repository="snowflakedb/snowpark-java-scala",
            artifact_type="jar",
            repo_root=self.repo_root,
            allow_non_release_ref=False,
        )
        with open(self.artifact_path, "rb") as fh:
            import hashlib

            self.assertEqual(record["sha256"], hashlib.sha256(fh.read()).hexdigest())


class AtomicWriteJsonTests(unittest.TestCase):
    def test_writes_readable_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_path = os.path.join(tmp, "nested", "out.json")
            gbp.atomic_write_json({"a": 1}, output_path)
            with open(output_path, encoding="utf-8") as fh:
                self.assertEqual(json.load(fh), {"a": 1})

    def test_no_leftover_temp_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_path = os.path.join(tmp, "out.json")
            gbp.atomic_write_json({"a": 1}, output_path)
            entries = os.listdir(tmp)
            self.assertEqual(entries, ["out.json"])

    def test_overwrites_existing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_path = os.path.join(tmp, "out.json")
            gbp.atomic_write_json({"a": 1}, output_path)
            gbp.atomic_write_json({"a": 2}, output_path)
            with open(output_path, encoding="utf-8") as fh:
                self.assertEqual(json.load(fh), {"a": 2})


class MainCliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo_root = self.tmp.name
        _init_git_repo(self.repo_root)
        self.artifact_path = os.path.join(self.repo_root, "snowpark_2.12.jar")
        with open(self.artifact_path, "wb") as fh:
            fh.write(b"fake jar bytes")
        self.output_path = os.path.join(self.repo_root, "provenance.json")

    def _run_main(self, extra_args):
        stderr = io.StringIO()
        stdout = io.StringIO()
        args = [
            "--artifact-path",
            self.artifact_path,
            "--gav",
            "com.snowflake:snowpark_2.12:4.3.4",
            "--ref",
            "v4.3.4",
            "--build-id",
            "12345",
            "--output",
            self.output_path,
            "--repo-root",
            self.repo_root,
        ] + extra_args
        with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(stdout):
            exit_code = gbp.main(args)
        return exit_code, stdout.getvalue(), stderr.getvalue()

    def test_success_writes_output_file(self):
        exit_code, _stdout, _stderr = self._run_main([])
        self.assertEqual(exit_code, 0)
        self.assertTrue(os.path.isfile(self.output_path))
        with open(self.output_path, encoding="utf-8") as fh:
            record = json.load(fh)
        self.assertEqual(record["schemaVersion"], 1)
        self.assertEqual(record["gav"], "com.snowflake:snowpark_2.12:4.3.4")

    def test_failure_does_not_write_output_file(self):
        exit_code, _stdout, stderr = self._run_main(["--ref", "not-a-release-tag"])
        self.assertEqual(exit_code, 1)
        self.assertFalse(os.path.isfile(self.output_path))
        self.assertIn("[ERROR]", stderr)

    def test_poc_flag_warns_on_stderr(self):
        exit_code, _stdout, stderr = self._run_main(
            ["--ref", "some-branch", "--gav", "com.snowflake:snowpark_2.12:4.3.4-poc", "--allow-non-release-ref"]
        )
        self.assertEqual(exit_code, 0)
        self.assertIn("[WARN]", stderr)
        self.assertIn("--allow-non-release-ref", stderr)


if __name__ == "__main__":
    unittest.main()
