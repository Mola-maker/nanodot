# Tmall Showcase Publication Plan

> **For agentic workers:** Use Superpowers executing-plans and verification-before-completion. The repository coordinator owns Git changes; the parent owns browser deployment and the homepage implementer owns the source until freeze.

**Goal:** Push and merge the reviewed Nanodot Tmall hackathon showcase as reproducible public source, with honest ModelScope deployment and collaboration instructions.

**Architecture:** Keep the canonical static hierarchy in `demo/hackathon/site/`. Export a separate ignored flat upload directory through `demo/hackathon/build-flat.py`; preserve binary bytes and record URL rewrites and hashes. Connect repository READMEs and bilingual docs to the showcase without changing Android release artifacts or cloud services.

**Tech Stack:** Static HTML/CSS/JavaScript, Python standard library, VitePress, GitHub pull requests, ModelScope Static hosting.

**Spec:** User requests to prepare the project for the Tmall hackathon and push first, with final personal application fields left to the user. The reviewed homepage, public evidence manifest and pinned upstream source define the deliverable.

## Global constraints

- Preserve tag `v1.1.0`, its signed APK and source commit; this is a separate website change.
- Import the frozen reviewed source only. Keep the real Android recording, scripted browser interaction and upstream demo attribution distinct.
- Publish no local paths, credentials, private logs or personal application information.
- Document manual GitHub-to-ModelScope synchronization; no automatic sync or model backend is configured by this change.
- Preserve the pinned upstream website snapshot, licence, attribution and original/vendored hashes.

## Review focus

- Relative URLs must resolve both in canonical hierarchy and flat upload form.
- Flat filenames must be unique and binary bytes must remain unchanged.
- Runtime claims must retain development APK identities and incomplete Care/voice/device acceptance.
- A static page or an upstream iframe must not imply a Nanodot cloud inference/control backend.
- Small screens, keyboard controls and release links require the parent's actual browser verification.

## Tasks

- [ ] After the explicit source freeze, copy the canonical site, provenance and cover prompt; make the flat exporter portable and ignore its generated output.
- [ ] Add bilingual repository/documentation links and manual collaboration instructions, plus an Unreleased changelog entry.
- [ ] Verify JavaScript syntax, canonical and exported references, manifests and asset hashes; build bilingual docs and review the final diff independently.
- [ ] Commit with DCO, push a separate branch, attach its pull request and merge after applicable checks pass. Let the parent verify the actual ModelScope deployment before updating the repository homepage URL.
