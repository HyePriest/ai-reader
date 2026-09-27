"""Build the reproducible, original bilingual fixture; no downloaded book text."""
from pathlib import Path
from zipfile import ZipFile, ZIP_STORED, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
assets = root / "app/src/main/assets"
assets.mkdir(parents=True, exist_ok=True)
sample = assets / "a-window-in-the-rain.epub"
chapters = [
    ("The window", """
<p><span data-er-id="s1">The rain began just as Nora opened the book.</span> <span data-er-id="s2">At first it was only a whisper against the window.</span> <span data-er-id="s3">Then the whole street seemed to grow quiet and listen.</span></p>
<p data-er-for="s1" lang="zh">诺拉刚翻开书，雨就下起来了。</p>
<p data-er-for="s2 s3" lang="zh">起初，雨只是在窗上轻声低语；后来，整条街仿佛都安静下来，侧耳倾听。</p>
<p><span data-er-id="s4">She had lived above the little shop for three years, but she still thought of the room as a place she was borrowing.</span> <span data-er-id="s5">There were boxes under the bed that she had never unpacked.</span></p>
<p data-er-for="s4" lang="zh">她在小店楼上住了三年，却仍觉得这个房间是暂借的落脚处。</p>
<p data-er-for="s5" lang="zh">床底下还有些箱子，她一直没有打开。</p>
<p><span data-er-id="s6">On the table stood a blue cup, an old lamp, and a letter addressed in handwriting she almost recognized.</span> <span data-er-id="s7">She turned the envelope over.</span> <span data-er-id="s8">There was no name on the back.</span></p>
<p data-er-for="s6" lang="zh">桌上摆着一只蓝杯子、一盏旧台灯，还有一封信。信封上的字迹让她感到似曾相识。</p>
<p data-er-for="s7 s8" lang="zh">她把信封翻过来，背面没有署名。</p>
<p><span data-er-id="s9">“Not yet,” she said, though there was nobody in the room to hear her.</span> <span data-er-id="s10">For now, she wanted only the rain, the light, and a few pages of somebody else's life.</span></p>
<p data-er-for="s9" lang="zh">“先放一放吧。”她说，尽管屋里并没有别人。</p>
<p data-er-for="s10" lang="zh">此刻，她只想要雨声、灯光，和几页属于别人的人生。</p>
<p><span data-er-id="s11">She read the first line twice.</span> <span data-er-id="s12">The second time, something in it seemed to open, like a door she had mistaken for a wall.</span></p>
<p data-er-for="s11" lang="zh">第一行，她读了两遍。</p>
<p data-er-for="s12" lang="zh">读第二遍时，文字里似乎有什么打开了——就像一扇曾被她当成墙壁的门。</p>
<p><span data-er-id="s13">Outside, someone hurried past with a newspaper held above their head.</span> <span data-er-id="s14">Nora smiled and moved her chair a little closer to the lamp.</span></p>
<p data-er-for="s13" lang="zh">窗外，有人把报纸举在头顶，匆匆走过。</p>
<p data-er-for="s14" lang="zh">诺拉笑了笑，把椅子往灯边挪近了一点。</p>
<p><span data-er-id="s15">It was a small movement, but the room felt more like hers after it.</span> <span data-er-id="s16">She began to read again.</span></p>
<p data-er-for="s15" lang="zh">只是一个小小的动作，房间却因此更像属于她了。</p>
<p data-er-for="s16" lang="zh">她又读了下去。</p>
"""),
    ("The letter", """
<p><span data-er-id="s1">By morning, the rain had stopped.</span> <span data-er-id="s2">The letter was still waiting beside the blue cup.</span></p>
<p data-er-for="s1" lang="zh">到了早晨，雨已经停了。</p>
<p data-er-for="s2" lang="zh">那封信仍在蓝杯子旁边，静静等着。</p>
<p><span data-er-id="s3">Nora carried it to the window, where the light was clearer.</span> <span data-er-id="s4">She did not open it immediately.</span></p>
<p><span data-er-id="s5">“Some things,” her mother used to say, “need a little space around them.”</span> <span data-er-id="s6">For the first time, Nora thought she understood.</span></p>
<p><span data-er-id="s7">She put the letter down and made a fresh cup of tea.</span> <span data-er-id="s8">When she came back, she was ready.</span></p>
"""),
    ("A little room", """
<p><span data-er-id="s1">That afternoon she opened the first box beneath the bed.</span> <span data-er-id="s2">Inside were three photographs, a green scarf, and a small wooden bird.</span></p>
<p><span data-er-id="s3">She set the bird on the windowsill.</span> <span data-er-id="s4">Its painted eyes looked out over the roofs as if they had always belonged there.</span></p>
<p><span data-er-id="s5">The shopkeeper called up from the stairs to ask if she needed anything.</span> <span data-er-id="s6">“A shelf,” she answered, surprising herself.</span></p>
<p><span data-er-id="s7">There was room, after all, for the things she had brought with her.</span> <span data-er-id="s8">And perhaps for a few things she had not yet found.</span></p>
"""),
]

def write(z, name, text, compression=ZIP_DEFLATED):
    from zipfile import ZipInfo
    info = ZipInfo(name, date_time=(2026, 9, 22, 0, 0, 0))
    info.compress_type = compression
    z.writestr(info, text.encode("utf-8"))

with ZipFile(sample, "w") as z:
    write(z, "mimetype", "application/epub+zip", ZIP_STORED)
    write(z, "META-INF/container.xml", '<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
    items = ''.join(f'<item id="c{i}" href="chapter{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(1, 4))
    spine = ''.join(f'<itemref idref="c{i}"/>' for i in range(1, 4))
    write(z, "OEBPS/content.opf", f'''<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">urn:englishreader:sample:window:1</dc:identifier><dc:title>A Window in the Rain</dc:title><dc:creator>EnglishReader · 原创体验短篇</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-09-22T00:00:00Z</meta></metadata><manifest>{items}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine>{spine}</spine></package>''')
    for i, (title, body) in enumerate(chapters, 1):
        write(z, f"OEBPS/chapter{i}.xhtml", f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" lang="en"><head><title>{title}</title></head><body><h1>{title}</h1>{body}</body></html>')
    links = ''.join(f'<li><a href="chapter{i}.xhtml">{title}</a></li>' for i, (title, _) in enumerate(chapters, 1))
    write(z, "OEBPS/nav.xhtml", f'<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><h1>Contents</h1><ol>{links}</ol></nav></body></html>')
print(sample)
