# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

The primary user configures and supervises a personal Soul chat automation tool on their own Android phone.

## Product Purpose

SoulBot automates two mutually exclusive workflows—Soul matching plus chat replies, or local-square browsing plus direct messages—while always prioritizing encounter-bell and unread-chat responses. Success means the user can understand the current state, configure behavior safely, and leave the running workflow under floating-window supervision.

## Operating Context

The app is a control and settings companion. Frequent actions are checking readiness and opening the floating controller; model credentials, persona, reply learning, and data backup are configured less often. The automation itself runs through an Android accessibility service against the Soul app.

## Capabilities and Constraints

- Existing preference keys, local databases, automation priority, and floating-window behavior must survive UI changes.
- Model generation uses a user-ordered active/standby queue with up to five independently configured channels, automatic failover, and per-channel cooldown.
- API credentials and conversation memory stay on the device unless the user explicitly exports them.
- Conversation-memory exports are plaintext JSON and imports merge without replacing existing records.
- Android system Back and accessibility settings are part of the expected workflow.
- The interface uses Android Views and Material Components, with a minimum supported SDK of 26.

## Brand Commitments

The product name is SoulBot 自动助手. Interface copy is direct, operational, and written in simplified Chinese.

## Product Principles

- Put current state and the next useful action first.
- Separate frequent controls from infrequent configuration and destructive data operations.
- Explain what each automation setting changes before the user saves it.
- Preserve local data and make risky operations explicit and recoverable through export.
- Keep automation state independent from navigation and configuration screens.
