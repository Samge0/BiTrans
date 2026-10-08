# -*- coding: utf-8 -*-
"""i18n audit v2: hardcoded CJK literals outside dictionaries/allowlist.
Allows: dictionary keys (t()/I18n.t()/format-wrapped), LLM prompts, date
patterns, TargetLang enum display names, engine name key, test data."""
import io, re, sys, os

ROOT = r"F:/Space/PRO/android/BiTrans/app/src/main/java/com/samge/bitrans"
SKIP_DIRS = {"i18n"}
cjk = re.compile(r'[\u4e00-\u9fff]')
lit = re.compile(r'"((?:[^"\\\n]|\\.)*)"')

ALLOW_SUBSTR = [
    "yyyy年M月d日",          # date pattern branches
]
ALLOW_EXACT = {
    "你起标题。只输出标题本身，不要引号不要解释，10字以内。",
    "请总结这段对话记录：讨论的主题、关键信息点、结论。用中文分点输出。",
    "你是一个对话记录分析助手。用户会提供一段语音翻译记录（原文|译文 每行一条），请基于它回答问题或做总结。回答使用中文。",
    "今天天气不错，我们去公园散步吧。",
    "中文", "粤语", "日本語", "MLKit(离线)",   # TargetLang/engine display keys → t() at usage
    "确定", "取消",                                # AppleDialog defaults
}
# prefix-templates (LLM prompts with embedded $var)
ALLOW_PREFIX = [
    "为以下对话记录起一个简短中文标题：",
    "对话记录如下：",
]

leaks = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for fn in filenames:
        if not fn.endswith(".kt"): continue
        p = os.path.join(dirpath, fn)
        for i, line in enumerate(io.open(p, encoding="utf-8"), 1):
            st = line.strip()
            if st.startswith("*") or st.startswith("//") or st.startswith("/*"): continue
            for m in lit.finditer(line):
                s = m.group(1)
                if not cjk.search(s): continue
                if s in ALLOW_EXACT: continue
                if any(a in s for a in ALLOW_SUBSTR): continue
                if any(s.startswith(a) for a in ALLOW_PREFIX): continue
                # t()-wrapped? look at what precedes the literal on this line
                pre = line[:m.start()]
                if re.search(r'\bt\(\s*$', pre) or re.search(r'\bt\(\s*[A-Za-z_][\w.]*\s*,\s*$', pre) \
                   or re.search(r'\bt\(\s*[\w.]+\(\)\s*,\s*$', pre) \
                   or re.search(r'\bformat\(\s*$', pre) or re.search(r'I18n\.t\(\s*$', pre):
                    continue
                leaks.append(f"{p}:{i}: {s[:60]}")

if leaks:
    print(f"LEAKS: {len(leaks)}")
    for l in leaks: print(" ", l)
    sys.exit(1)
print("AUDIT CLEAN")
