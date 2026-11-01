---
layout: home
title: Syleks
description: Your AI coding agent, directly in your terminal.
hero:
  name: Syleks
  text: Your AI coding agent, directly in your terminal.
  tagline: Explore code, collaborate with agents, and return to persistent conversations. Connect your model and extend your workflow with permission-checked tools and MCP servers.
  actions:
    - theme: brand
      text: Get Started
      link: /quickstart
    - theme: alt
      text: View on GitHub
      link: https://github.com/fpapon/syleks
features:
  - title: Terminal native
    details: A responsive full-screen interface with streaming answers, multiline input, keyboard controls and change review.
  - title: Your choice of model
    details: Use local Ollama models, OpenAI-compatible servers, or built-in cloud providers. Provider capabilities and authentication vary.
  - title: Conversations that continue
    details: Local SQLite storage keeps project sessions, forks, structured tool history and token metrics across launches.
  - title: Agents with boundaries
    details: Switch primary agents, delegate bounded tasks to specialists, and review permission-checked tool actions.
  - title: Tools and MCP
    details: Connect external MCP tools, resources and prompts alongside native file, command and web tools.
  - title: Working-source MVP
    details: Build from source with Java 25. Review platform requirements and verify your chosen terminal and model server.
---

<TerminalPreview />

<script setup>
import TerminalPreview from './.vitepress/theme/TerminalPreview.vue'
</script>

<div class="home-next">

## A short path to your first session

1. [Install from source](installation.md) with Java 25 and Maven.
2. [Connect Ollama and start chatting](quickstart.md).
3. [Learn the composer and shortcuts](chat.md).
4. [Resume sessions](sessions.md) and [choose an agent](agents.md).
5. [Connect an MCP server](mcp.md) when you need external tools.

</div>
