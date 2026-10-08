#let setup(title, subtitle, body) = {
  set document(title: title, author: "출퇴근 시스템")
  set text(font: "Noto Sans CJK KR", lang: "ko", size: 10pt)
  set page(paper: "a4", margin: (x: 21mm, y: 20mm), footer: context [#text(size: 8pt, fill: gray)[출퇴근 · v0.3.0 · #counter(page).display() / #counter(page).final().at(0)]])
  set par(leading: 0.7em)
  set heading(numbering: "1.1")
  show heading.where(level: 1): it => block(above: 1.2em, below: 0.7em)[#text(fill: rgb("17436a"), size: 17pt, weight: "bold", it.body)]
  text(size: 27pt, weight: "bold", fill: rgb("17436a"), title)
  linebreak()
  text(size: 12pt, subtitle)
  parbreak()
  [작성일: 2026년 10월 8일 · 대상: Android 12, Windows 11]
  parbreak()
  body
}
#let note(body) = block(fill: rgb("edf4fb"), inset: 12pt, radius: 4pt, width: 100%, body)
