# Accessibility audit — RC1

Implemented guardrails:
- interactive controls receive at least a 48 dp touch target;
- image actions require content descriptions;
- keyboard/TalkBack forward focus follows visual order;
- large-font layout uses taller navigation and adaptive column counts;
- text remains system-scalable;
- red destructive actions include text labels and are not color-only;
- icon-only top actions expose Russian descriptions.

On-device checks still required: TalkBack reading order, switch announcements, 200% font scale, dark-theme contrast and landscape tablet focus order.
