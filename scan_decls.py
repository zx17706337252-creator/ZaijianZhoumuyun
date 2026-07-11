#!/usr/bin/env python3
"""
scan_decls.py — scan a Kotlin file for TOP-LEVEL declarations (fun/class/object/val/var/const val,
including enum class / data class / sealed class / abstract class variants) and report their
[start_line, end_line] (1-indexed, inclusive).

Design notes (fixes vs. the earlier broken version):
  1. Modifier prefix handling: `enum`, `data`, `sealed`, `abstract`, `open`, `inner`, `annotation`,
     `private`, `internal`, `public`, `protected`, `const`, `suspend`, `inline`, `override` are all
     treated as optional prefix tokens before the real keyword (fun/class/object/val/var/interface).
  2. A declaration only starts at brace_depth == 0 AND paren_depth == 0 (i.e. genuinely top-level,
     not inside another declaration's body or parameter list).
  3. Three distinct ways a top-level declaration can end, tracked independently:
       a) Brace body: once we've seen the opening top-level '{', end when brace_depth returns to 0.
       b) Class-like with only a primary constructor (no brace body): e.g. `data class Foo(...)`.
          Ends when paren_depth returns to 0 on a line where no top-level '{' was ever opened,
          AND the line's last non-whitespace char is ')' (or ')' followed by ':' supertype list
          that itself closes with '{' — handled by the brace path instead).
       c) Expression / property body via '=': e.g. `val x = 1`, `fun f() = ...`, possibly spanning
          multiple lines closed by matching parens/brackets/braces going back to depth 0.
          has_eq_at_top is a CUMULATIVE flag: True if '=' was seen at paren_depth==0 &&
          brace_depth==0 on ANY line since the declaration started (not just the current line).
  4. Single-line declarations (start and end on the same line) are detected directly.

This is a heuristic line/char scanner, not a real Kotlin parser. It ignores strings/comments
reasonably well (enough for this codebase) but is not bulletproof against pathological input.
"""
import re
import sys
from dataclasses import dataclass, field

MODIFIER_PREFIXES = {
    "private", "internal", "public", "protected",
    "enum", "data", "sealed", "abstract", "open", "inner", "annotation",
    "const", "suspend", "inline", "override", "operator", "infix",
    "tailrec", "external", "actual", "expect", "vararg", "crossinline",
    "noinline", "reified", "companion", "lateinit", "value", "fun",
}

# The actual "kind" keywords we care about capturing as top-level declarations.
KIND_KEYWORDS = {"fun", "class", "object", "val", "var", "interface", "typealias"}

DECL_START_RE = re.compile(
    r'^\s*(?:(?:' + '|'.join(MODIFIER_PREFIXES) + r')\s+)*'
    r'(fun|class|object|val|var|interface|typealias)\b'
)

NAME_RE_BY_KIND = {
    "fun": re.compile(r'\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?([A-Za-z_][\w]*)'),
    "class": re.compile(r'\bclass\s+([A-Za-z_][\w]*)'),
    "object": re.compile(r'\bobject\s+([A-Za-z_][\w]*)'),
    "val": re.compile(r'\bval\s+([A-Za-z_][\w]*)'),
    "var": re.compile(r'\bvar\s+([A-Za-z_][\w]*)'),
    "interface": re.compile(r'\binterface\s+([A-Za-z_][\w]*)'),
    "typealias": re.compile(r'\btypealias\s+([A-Za-z_][\w]*)'),
}


def strip_strings_and_comments(line, in_block_comment, mode_stack=None):
    """Best-effort: blank out string/char literal contents and comments so brace/paren
    counting isn't fooled by them, EXCEPT for Kotlin string-template interpolations
    (${...}), whose contents are real code and must keep contributing to brace/paren
    depth (e.g. `"${ when (x) { ... } }"`).

    State model: mode_stack is a persistent (across-lines) stack of frames, each one
    of:
      ('str', )        - inside a regular "..." string's literal text
      ('triple', )     - inside a triple-quoted \"\"\"...\"\"\" string's literal text
      ('code', depth)  - inside a ${...} interpolation; depth = nested {} seen so far
                          within this interpolation (so we know which '}' closes it)
    The stack lets ${...} appear inside a string which itself might be inside another
    interpolation's code, etc. Top of stack = current context. Empty stack = top-level
    code (outside any string).

    in_block_comment is handled separately as before (comments can't appear inside
    strings so this doesn't interact with mode_stack).

    Returns (cleaned_line, still_in_block_comment, mode_stack).
    """
    if mode_stack is None:
        mode_stack = []
    out = []
    i = 0
    n = len(line)

    while i < n:
        if in_block_comment:
            end = line.find("*/", i)
            if end == -1:
                i = n
                continue
            i = end + 2
            in_block_comment = False
            continue

        top = mode_stack[-1] if mode_stack else ('code',)
        kind = top[0]

        if kind == 'str':
            ch = line[i]
            if ch == '\\' and i + 1 < n:
                i += 2
                continue
            if ch == '$' and i + 1 < n and line[i+1] == '{':
                mode_stack.append(('code', 0))
                out.append('  ')
                i += 2
                continue
            if ch == '"':
                mode_stack.pop()
                out.append(ch)
                i += 1
                continue
            i += 1
            continue

        if kind == 'triple':
            if line[i:i+3] == '"""':
                mode_stack.pop()
                out.append('""')
                i += 3
                continue
            if line[i] == '$' and i + 1 < n and line[i+1] == '{':
                mode_stack.append(('code', 0))
                out.append('  ')
                i += 2
                continue
            i += 1
            continue

        # kind == 'code' (either true top-level, or inside a ${...} interpolation)
        ch = line[i]
        if line[i:i+3] == '"""':
            mode_stack.append(('triple',))
            out.append('""')
            i += 3
            continue
        if ch == '"':
            mode_stack.append(('str',))
            out.append(ch)
            i += 1
            continue
        if ch == "'":
            # char literal: consume until closing ' (handling escape)
            out.append(ch)
            i += 1
            while i < n:
                if line[i] == '\\' and i + 1 < n:
                    i += 2
                    continue
                if line[i] == "'":
                    out.append(line[i])
                    i += 1
                    break
                i += 1
            continue
        if line[i:i+2] == '//':
            break
        if line[i:i+2] == '/*':
            in_block_comment = True
            i += 2
            continue
        if ch == '{':
            if mode_stack and mode_stack[-1][0] == 'code' and len(mode_stack) > 0 \
                    and _is_interp_frame(mode_stack):
                mode_stack[-1] = ('code', mode_stack[-1][1] + 1)
            out.append(ch)
            i += 1
            continue
        if ch == '}':
            if mode_stack and _is_interp_frame(mode_stack):
                depth = mode_stack[-1][1]
                if depth == 0:
                    mode_stack.pop()  # this '}' closes the ${...}
                    out.append(' ')
                    i += 1
                    continue
                else:
                    mode_stack[-1] = ('code', depth - 1)
                    out.append(ch)
                    i += 1
                    continue
            out.append(ch)
            i += 1
            continue
        out.append(ch)
        i += 1

    return ''.join(out), in_block_comment, mode_stack


def _is_interp_frame(mode_stack):
    """True if the top of the stack represents a ${...} interpolation frame
    (i.e. a 'code' frame that is NOT the outermost implicit top-level code)."""
    if not mode_stack:
        return False
    if mode_stack[-1][0] != 'code':
        return False
    # An implicit top-level 'code' state is represented by an EMPTY stack, not a
    # ('code', n) frame — any ('code', n) frame on the stack was pushed by a '${',
    # so it's always a real interpolation frame.
    return True


@dataclass
class Decl:
    kind: str
    name: str
    start: int
    end: int = None


def scan(path):
    with open(path, 'r', encoding='utf-8') as f:
        raw_lines = f.readlines()

    decls = []
    cur = None  # Decl in progress
    brace_depth = 0
    paren_depth = 0
    bracket_depth = 0
    seen_body_open = False       # have we opened a top-level '{' for the current decl?
    has_eq_at_top = False        # CUMULATIVE: '=' seen at depth 0 since decl started
    in_block_comment = False
    mode_stack = []               # persists across lines: string/triple/interpolation frames

    for lineno, raw in enumerate(raw_lines, start=1):
        line, in_block_comment, mode_stack = strip_strings_and_comments(
            raw, in_block_comment, mode_stack)
        stripped = line.strip()

        # Try to start a new declaration only when we're fully at top level and none in progress.
        if cur is None and brace_depth == 0 and paren_depth == 0 and bracket_depth == 0:
            m = DECL_START_RE.match(line)
            if m and stripped and not stripped.startswith('@'):
                kind = m.group(1)
                if kind in KIND_KEYWORDS:
                    name_m = NAME_RE_BY_KIND[kind].search(line)
                    name = name_m.group(1) if name_m else '<anon>'
                    cur = Decl(kind=kind, name=name, start=lineno)
                    seen_body_open = False
                    has_eq_at_top = False
                    # fallthrough: still need to process this line's chars below

        if cur is None:
            continue

        # Walk characters on this (comment/string-stripped) line to update depth counters,
        # and detect a top-level '=' (cumulative) and a top-level '{' opening.
        line_had_top_eq = False
        for ch in line:
            if ch == '{':
                if brace_depth == 0:
                    seen_body_open = True
                brace_depth += 1
            elif ch == '}':
                brace_depth = max(0, brace_depth - 1)
            elif ch == '(':
                paren_depth += 1
            elif ch == ')':
                paren_depth = max(0, paren_depth - 1)
            elif ch == '[':
                bracket_depth += 1
            elif ch == ']':
                bracket_depth = max(0, bracket_depth - 1)
            elif ch == '=' :
                # top-level '=' means: not inside parens/brackets, and either not yet in a brace
                # body, or right at brace_depth 0 (property/fun expression body assignment).
                if paren_depth == 0 and bracket_depth == 0 and brace_depth == 0:
                    line_had_top_eq = True

        if line_had_top_eq:
            has_eq_at_top = True

        last_char = stripped[-1] if stripped else ''

        closed = False

        # (a) Brace-bodied declaration: ends when brace_depth returns to 0 after having opened one.
        if seen_body_open and brace_depth == 0 and paren_depth == 0 and bracket_depth == 0:
            closed = True

        # (b) class-like with only a primary constructor / supertype list, no brace body at all:
        #     ends when paren_depth (and bracket/brace) returns to 0 and the line ends with ')'
        #     (covers `data class Foo(...)`, possibly multi-line constructor params).
        elif (not seen_body_open) and cur.kind in ('class', 'interface') \
                and paren_depth == 0 and bracket_depth == 0 and brace_depth == 0 \
                and last_char == ')':
            closed = True

        # (c) expression/property body via '=' (cumulative), closing back to depth 0, e.g.
        #     `val x = 1`, `fun f(...) = expr` possibly spanning multiple lines via parens.
        elif (not seen_body_open) and has_eq_at_top \
                and paren_depth == 0 and bracket_depth == 0 and brace_depth == 0 \
                and last_char not in ('', '=', ',', '(', '{', '['):
            closed = True

        # (d) plain single-line val/var/typealias with no '=' and no braces at all
        #     e.g. `val x: Foo` (rare) — treat as closed once depth 0 and line non-empty and
        #     not obviously continuing (ends with identifier-ish char, ';', or a type).
        elif (not seen_body_open) and (not has_eq_at_top) and cur.kind in ('val', 'var', 'typealias') \
                and paren_depth == 0 and bracket_depth == 0 and brace_depth == 0 \
                and last_char not in ('', ',', '(', '{', '[', ':', '<', '.', '&', '|'):
            closed = True

        if closed:
            cur.end = lineno
            decls.append(cur)
            cur = None
            seen_body_open = False
            has_eq_at_top = False

    if cur is not None:
        # Unterminated at EOF — report best effort.
        cur.end = len(raw_lines)
        decls.append(cur)

    return decls


def main():
    if len(sys.argv) < 2:
        print("usage: scan_decls.py <file.kt> [file2.kt ...]")
        sys.exit(1)
    for path in sys.argv[1:]:
        decls = scan(path)
        print(f"=== {path} ({len(decls)} top-level declarations) ===")
        for d in decls:
            print(f"{d.kind:10s} {d.name:30s} {d.start}-{d.end}")
        print()


if __name__ == '__main__':
    main()
