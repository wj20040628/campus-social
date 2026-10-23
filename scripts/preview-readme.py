"""
把 README.md 渲染成可直接用浏览器打开的 HTML，用于人工核对版面。

【为什么需要】
README 里的并排表格、details 折叠块、图片尺寸，在纯文本里看不出效果。
更关键的是：Markdown 中的 HTML 块（<div> / <details>）默认**不解析块内 Markdown**，
少写 markdown="1" 就会让整块首屏被原样输出成文本 —— 本脚本一眼能看出这个问题。

用法：
    python scripts/preview-readme.py
    然后打开生成的 .readme-preview.html
"""
import pathlib

import markdown

md = pathlib.Path("README.md").read_text(encoding="utf-8")
html = markdown.markdown(
    md,
    extensions=["tables", "fenced_code", "md_in_html", "attr_list"],
)

# 模拟 GitHub 的容器宽度与排版
page = f"""<!doctype html><html><head><meta charset="utf-8">
<style>
body {{ max-width: 900px; margin: 0 auto; padding: 40px 24px 120px;
  font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 16px; line-height: 1.6; color: #1f2328; background: #fff; }}
img {{ max-width: 100%; height: auto; }}
table {{ border-collapse: collapse; width: 100%; }}
th, td {{ border: 1px solid #d0d7de; padding: 6px 13px; }}
th {{ background: #f6f8fa; }}
code {{ background: #f6f8fa; padding: .2em .4em; border-radius: 6px; font-size: 85%; }}
pre {{ background: #f6f8fa; padding: 16px; border-radius: 6px; overflow: auto; }}
pre code {{ background: none; padding: 0; }}
h1, h2 {{ border-bottom: 1px solid #d8dee4; padding-bottom: .3em; }}
blockquote {{ border-left: 4px solid #d0d7de; margin: 0; padding: 0 1em; color: #656d76; }}
details {{ border: 1px solid #d0d7de; border-radius: 6px; padding: 8px 12px; }}
summary {{ cursor: pointer; }}
</style></head><body>
{html}
</body></html>"""

out = pathlib.Path(".readme-preview.html")
out.write_text(page, encoding="utf-8")
print(f"已生成 {out}（{len(page)} 字符）")
