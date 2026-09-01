# Conditions

Conditions let a format change itself based on what a placeholder says. You declare a named test in
`config.yml`, then drop `%condition:name%` into any format and LPC substitutes one of two strings
depending on whether the test passed.

They are modelled on [TAB's conditional placeholders](https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Conditional-placeholders),
so the syntax should look familiar if you have used those.

## A first example

```yaml
conditions:
  example:
    conditions:
      - "%player_world%=world_nether"
    yes: "<red>[NETHER] "
    no: ""

chat-format: "%condition:example%%rank_prefix% <gray>| <white>{name}<dark_gray> »<reset> {message}"
```

Players in the nether get `[NETHER]` in front of their rank; everyone else gets nothing, because
`no` is an empty string.

## Viewer conditions

`%condition:name%` asks about the player who **wrote** the line. `%vcondition:name%` asks about the
player who is **reading** it. Chat is rendered once per viewer, so a single format may use both.

The branch a viewer condition picks is chosen using the reader's placeholders, but the text inside
that branch is still expanded against the speaker. That split is the whole point: it lets a reader
hide something belonging to the speaker.

```
conditions:
  ranks:
    conditions:
      - "%lpc_hide_ranks%=true"
    "yes": ""
    "no": "%rank_prefix% <gray>| "

chat-format: "%vcondition:ranks%<white>{name}<dark_gray> »<reset> {message}"
```

Here `%lpc_hide_ranks%` is read for the viewer, while `%rank_prefix%` in the `no` branch still
resolves to the *speaker's* rank. Readers who ran `/hideranks on` see a clean `Name » message`.

Viewer conditions only apply where a viewer exists — the Paper chat listener. On the legacy Spigot
listener, which renders one line for the whole audience, they fall back to the speaker. Nesting
stays inside one namespace: a viewer condition's branches may only name other viewer conditions.

## Where you can use them

`%condition:name%` works in every operator-authored format (and `%vcondition:name%` in the chat
formats, which are the ones with a viewer):

- `chat-format`
- `group-formats` and `track-formats`
- `join-messages`, `quit-messages`, `death-messages`
- `group-message-styles`, `track-message-styles`, `default-message-style`

It does **not** work inside a player's own chat message. That is deliberate: player text is never
re-parsed, so nobody can type `%condition:...%` and have it evaluated.

## Writing a condition

```yaml
conditions:
  <name>:
    # ALL  - every line below must pass (this is the default)
    # ANY  - at least one line must pass
    type: ALL
    conditions:
      - "%placeholder%=value"
      - "%another%>=10"
    yes: "text when the condition passes"
    no: "text when it does not"
```

Names may contain letters, digits, `_`, `.` and `-`.

Both `yes` and `no` default to an empty string, so you can leave one out when you only want output
in one case.

> **Note on `yes` / `no`.** YAML 1.1 — which the config loader uses — reads bare `yes` and `no` as
> booleans, so they arrive as the keys `true` and `false`. LPC accepts either spelling, so
> `yes:`, `"yes":` and `true:` all work. If you are hand-editing a config written by an older
> build, quoting the keys is the safest choice.

## Operators

| Operator | Meaning | Example |
|---|---|---|
| `=` | text is exactly equal | `%player_world%=world_nether` |
| `!=` | text is not equal | `%player_world%!=world` |
| `|-` | left contains right | `%luckperms_groups%\|-builder` |
| `>` | greater than (numeric) | `%player_health%>10` |
| `>=` | at least (numeric) | `%vault_eco_balance%>=1000` |
| `<` | less than (numeric) | `%player_health%<5` |
| `<=` | at most (numeric) | `%player_ping%<=100` |
| `permission:` | the player has the node | `permission:group.vip` |

Notes:

- Text comparisons are **case-sensitive**. `%player_world%=World` will not match `world`.
- Numeric comparisons tolerate thousands separators, so `1,250.5` is read as `1250.5`.
- If either side of a numeric comparison is not a number — usually an unresolved placeholder because
  the plugin providing it is missing — the comparison is simply **false**. It never errors, so one
  bad placeholder cannot break a whole chat format.
- An operator character inside a placeholder name is not mistaken for the operator, so
  `%server_online>=5%=yes` compares the placeholder `%server_online>=5%` against `yes`.

## More examples

**Rich players in the nether** (both must hold, so `type` can be left out):

```yaml
conditions:
  nether_rich:
    conditions:
      - "%player_world%=world_nether"
      - "%vault_eco_balance%>=1000"
    yes: "<gradient:#ff0000:#ffaa00>[NETHER RICH]</gradient> "
```

**Staff badge, by permission:**

```yaml
conditions:
  staff:
    conditions:
      - "permission:group.staff"
    yes: "<red>[STAFF] "
```

**Low health warning, either of two ways** (`ANY`):

```yaml
conditions:
  hurt:
    type: ANY
    conditions:
      - "%player_health%<5"
      - "%player_food_level%<5"
    yes: "<dark_red>✖ "
```

**AFK or not:**

```yaml
conditions:
  afk:
    conditions:
      - "%essentials_afk%=yes"
    yes: "<gray>[AFK] "
    no: ""
```

## Things worth knowing

- **Placeholders need PlaceholderAPI.** Without it installed, `%player_world%` stays literal text
  and will not equal anything you compare it to. Comparisons between literals (`1=1`) still work.
- **Output is trusted.** `yes` and `no` come from your config, so MiniMessage tags in them are
  rendered, and any placeholders in them are expanded afterwards. Treat them like any other part of
  your format.
- **Conditions can nest.** A condition's `yes` or `no` may contain `%condition:other%`. Nesting is
  capped at 10 levels, so a condition that refers to itself stops instead of looping.
- **A typo stays visible.** `%condition:tpyo%` is left in the message rather than silently blanked,
  so you can see what went wrong.
- **A condition with no valid lines never passes**, and always uses its `no` value. LPC logs a
  warning at startup for lines it could not parse, naming the condition.
- **`/lpc reload` re-reads conditions**, so you can iterate without restarting.
