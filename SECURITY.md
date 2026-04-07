<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Security Policy

## Supported Versions

| Version | Supported |
| ------- | --------- |
| latest  | ✅        |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

To report a security vulnerability, email **security@miniforge.ai** with:

- A description of the vulnerability and its potential impact
- Steps to reproduce or proof-of-concept code
- Any suggested mitigations you are aware of

You should receive an acknowledgement within 48 hours. We will keep you informed as we investigate and address the
issue. We ask that you give us reasonable time to fix the vulnerability before public disclosure.

## Scope

The following are in scope for security reports:

- Agent prompt injection vulnerabilities
- Credential or secret leakage from workflow execution
- Unauthorized code execution or sandbox escapes in workflow phases
- Authentication or authorization bypasses in the web dashboard or MCP server

## Out of Scope

- Issues that require physical access to a machine
- Social engineering attacks
- Vulnerabilities in third-party dependencies (please report those upstream)
- Issues in example or demo configurations that are explicitly marked as non-production

## Preferred Languages

We prefer reports in English.
