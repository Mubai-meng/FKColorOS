# -*- coding: utf-8 -*-
"""把 paths.txt 里提取到的图标路径数据生成 Kotlin 源码。
不手抄，全部来自 material-icons-extended:1.7.8 字节码。
"""
import re, os, sys, json, collections

HERE = os.path.dirname(os.path.abspath(__file__))
txt = open(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'paths.txt'),
           encoding='utf-8').read()
blocks = re.split(r'^### ', txt, flags=re.M)[1:]

data = {}
for b in blocks:
    lines = b.strip('\n').split('\n')
    f = lines[0].split('|')
    name, pkg, am, nseg = f[0], f[1], int(f[2]), int(f[3])
    segs = []
    cur = None
    for l in lines[1:]:
        if l.startswith('SEG '):
            cur = []
            segs.append(cur)
        elif l.startswith('PATH ') and cur is not None:
            cur.append(l[5:])
    style = pkg.split('.')[-1]
    auto = pkg.split('.')[-2] == 'automirrored'
    data[name] = dict(style=style, auto=auto, am=bool(am), segs=segs, nseg=nseg)

CORE = set("""automirrored/rounded/KeyboardArrowRight filled/Home filled/Lock rounded/Check
outlined/Info outlined/Search filled/PlayArrow filled/MoreVert filled/Check filled/Close
automirrored/rounded/ArrowBack rounded/Info rounded/Settings rounded/Delete""".split())

# arcTo/arcToRelative 的第 4、5 个参数是 Boolean，提取时是 1.0f/0.0f
BOOL_ARG = {'arcTo': (3, 4), 'arcToRelative': (3, 4)}


def conv(cmd):
    m = re.match(r'([a-zA-Z]+)\((.*)\)$', cmd)
    if not m:
        return cmd
    fn, argstr = m.group(1), m.group(2)
    if fn == 'close' or not argstr.strip():
        return fn + '()'
    args = [a.strip() for a in argstr.split(',')]
    if fn in BOOL_ARG:
        for i in BOOL_ARG[fn]:
            args[i] = 'true' if float(args[i].rstrip('f')) != 0.0 else 'false'
    return '%s(%s)' % (fn, ', '.join(args))


HEADER = '''/*
 * 手写 Material 图标：替代 material-icons-extended 依赖（该库约 19MB dex 字节码，
 * 全工程只用了几十个图标）。
 *
 * 数据来源：androidx.compose.material:material-icons-extended:1.7.8 的字节码，
 * 由 asm 从 ImageVector.Builder / PathBuilder 的调用序列机械还原，
 * 坐标与原库逐字节一致（非手抄），视觉上无任何变化。
 *
 * 只包含本工程实际使用、且 material-icons-core 未自带的图标。
 * 新增图标：先确认 material-icons-core 有没有；没有就按本文件格式补一段，
 * 或临时把 material-icons-extended 加回来跑 tools/icon-extract/ 的提取脚本。
 */
package {pkg}

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
'''

ICON_TMPL = '''
val Icons.{receiver}.{name}: ImageVector
    get() {{
        if (_{lc} != null) return _{lc}!!
        _{lc} = ImageVector.Builder(
            name = "{full}",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,{auto_arg}
        ).apply {{
{paths}
        }}.build()
        return _{lc}!!
    }}

private var _{lc}: ImageVector? = null
'''

PATH_BLOCK = '''            path(fill = SolidColor(Color.Black)) {{
{cmds}
            }}
'''


def gen_icon(name, d):
    receiver = ('AutoMirrored.' if d['auto'] else '') + d['style'].capitalize()
    full = ('AutoMirrored.' if d['auto'] else '') + d['style'].capitalize() + '.' + name
    lc = name[0].lower() + name[1:]
    blocks = []
    for seg in d['segs']:
        cmds = '\n'.join('                ' + conv(c) for c in seg)
        blocks.append(PATH_BLOCK.format(cmds=cmds))
    return ICON_TMPL.format(
        receiver=receiver, name=name, lc=lc, full=full,
        auto_arg='\n            autoMirror = true,' if d['auto'] else '',
        paths=''.join(blocks).rstrip('\n'),
    )


groups = collections.defaultdict(list)
for n, d in data.items():
    key = ('automirrored/' if d['auto'] else '') + d['style'] + '/' + n
    if key in CORE:
        continue
    groups[(d['style'], d['auto'])].append(n)

# 脚本位于 <工程根>/tools/icon-extract/，所以往上两级才是工程根
outdir = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      '../../app/src/main/java/com/fr/colorostools/ui/icons')
os.makedirs(outdir, exist_ok=True)
mapping = []
for (style, auto), names in sorted(groups.items()):
    pkg = 'com.fr.colorostools.ui.icons.' + (('automirrored.' if auto else '') + style)
    fname = ('AutoMirrored' if auto else '') + style.capitalize() + 'Icons.kt'
    src = [HEADER.format(pkg=pkg).rstrip('\n')]
    for n in sorted(names):
        src.append(gen_icon(n, data[n]).rstrip('\n'))
    body = '\n'.join(src).rstrip('\n') + '\n'
    path = os.path.join(outdir, fname)
    open(path, 'wb').write(body.encode('utf-8'))
    for n in sorted(names):
        old = 'androidx.compose.material.icons.' + ('automirrored.' if auto else '') + style + '.' + n
        new = pkg + '.' + n
        mapping.append((old, new))
    print('%-30s %2d icons  %6d bytes' % (fname, len(names), len(body)))

json.dump(mapping, open(os.path.join(HERE, 'import_map.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=1)
print('total mapping', len(mapping))
