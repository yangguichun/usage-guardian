# -*- coding: utf-8 -*-
"""
生成「使用守护」的初始题库（seed）。

输出 static/quiz_seed.json，服务端首次启动时自动导入。三套题库：

  1. 语文 · 拼音认读   —— 给汉字选拼音 / 给拼音选汉字
  2. 数学 · 20 以内加减 —— 加、减、填空三类，程序生成
  3. 英语 · 牛津树 1-3 级 —— 英译中、中译英、补全单词、句子选词

★ 为什么要用脚本生成而不是手写 JSON：
   数学题需要"选项必须互不相同、不能出现负数"这类约束，手写一定会错；
   拼音的四声变体也是机械变换，用脚本才能保证每个选项都是合法的拼音串。

★ 拼音正确性是这套题库的底线 —— 家长一眼就能看出错。下面的汉字表只用
   读音确定无疑的常用字，且只对"有明确声调"的字出题（轻声字跳过）。
"""
import io
import json
import os
import random

random.seed(20261110)

TONE = {
    'a': 'āáǎà', 'e': 'ēéěè', 'i': 'īíǐì',
    'o': 'ōóǒò', 'u': 'ūúǔù', 'v': 'ǖǘǚǜ',
}
FLAT = {}
for base, marks in TONE.items():
    for k, ch in enumerate(marks):
        FLAT[ch] = (base, k)


def split_tone(py):
    """把带声调的拼音拆成 (基础音节, 声调索引)；没有声调标记返回 (原文, -1)"""
    for i, ch in enumerate(py):
        if ch in FLAT:
            base, tone = FLAT[ch]
            return py[:i] + base + py[i + 1:], tone
    return py, -1


def with_tone(flat, tone):
    """给无声调音节加第 tone 声（0..3）。找不到元音就返回 None。"""
    # 标调规则：有 a 标 a，没 a 标 o/e，iu/ui 标后者，否则标唯一的元音
    order = []
    if 'a' in flat:
        idx = flat.index('a')
    elif 'o' in flat:
        idx = flat.index('o')
    elif 'e' in flat:
        idx = flat.index('e')
    elif 'iu' in flat:
        idx = flat.index('iu') + 1
    elif 'ui' in flat:
        idx = flat.index('ui') + 1
    else:
        cand = [i for i, c in enumerate(flat) if c in TONE]
        if not cand:
            return None
        idx = cand[-1]
    base = flat[idx]
    marks = TONE[base]
    return flat[:idx] + marks[tone] + flat[idx + 1:]


def tone_variants(py):
    """同一音节的另外三个声调"""
    flat, tone = split_tone(py)
    if tone < 0:
        return []
    out = []
    for t in range(4):
        if t == tone:
            continue
        v = with_tone(flat, t)
        if v and v != py:
            out.append(v)
    return out


INITIAL_SWAP = [('zh', 'z'), ('z', 'zh'), ('ch', 'c'), ('c', 'ch'),
                ('sh', 's'), ('s', 'sh'), ('n', 'l'), ('l', 'n'),
                ('f', 'h'), ('h', 'f')]


def initial_variants(py):
    """平翘舌 / n-l / f-h 混淆的写法（保留原声调）"""
    flat, tone = split_tone(py)
    if tone < 0:
        return []
    out = []
    for a, b in INITIAL_SWAP:
        if flat.startswith(a):
            out.append(with_tone(b + flat[len(a):], tone))
    return [x for x in out if x and x != py]


def pinyin_distractors(py, n=3):
    """凑够 n 个"看着像、但读音不对"的选项"""
    pool = tone_variants(py) + initial_variants(py)
    # 去重且不许等于正确答案
    seen, out = {py}, []
    for p in pool:
        if p not in seen:
            seen.add(p)
            out.append(p)
    # 不够就用四声循环补齐（极少数轻声/短音节）
    if len(out) < n:
        flat, tone = split_tone(py)
        for t in range(4):
            v = with_tone(flat, t)
            if v and v not in seen:
                seen.add(v)
                out.append(v)
            if len(out) >= n:
                break
    return out[:n]


# ---------------------------------------------------------------- 语文
# 汉字 → 拼音。只收读音唯一的常用字。
HANZI = [
    ('爸', 'bà'), ('妈', 'mā'), ('哥', 'gē'), ('弟', 'dì'), ('姐', 'jiě'),
    ('妹', 'mèi'), ('鱼', 'yú'), ('鸟', 'niǎo'), ('花', 'huā'), ('草', 'cǎo'),
    ('树', 'shù'), ('水', 'shuǐ'), ('火', 'huǒ'), ('山', 'shān'), ('田', 'tián'),
    ('车', 'chē'), ('门', 'mén'), ('手', 'shǒu'), ('口', 'kǒu'), ('头', 'tóu'),
    ('白', 'bái'), ('黑', 'hēi'), ('红', 'hóng'), ('黄', 'huáng'), ('蓝', 'lán'),
    ('绿', 'lǜ'), ('春', 'chūn'), ('夏', 'xià'), ('秋', 'qiū'), ('冬', 'dōng'),
    ('风', 'fēng'), ('雨', 'yǔ'), ('雪', 'xuě'), ('云', 'yún'), ('月', 'yuè'),
    ('星', 'xīng'), ('天', 'tiān'), ('地', 'dì'), ('人', 'rén'), ('大', 'dà'),
    ('小', 'xiǎo'), ('多', 'duō'), ('少', 'shǎo'), ('上', 'shàng'), ('下', 'xià'),
    ('左', 'zuǒ'), ('右', 'yòu'), ('前', 'qián'), ('后', 'hòu'), ('学', 'xué'),
    ('书', 'shū'), ('笔', 'bǐ'), ('纸', 'zhǐ'), ('灯', 'dēng'), ('桌', 'zhuō'),
    ('床', 'chuáng'), ('饭', 'fàn'), ('菜', 'cài'), ('牛', 'niú'), ('羊', 'yáng'),
    ('马', 'mǎ'), ('猫', 'māo'), ('狗', 'gǒu'), ('鸡', 'jī'), ('鸭', 'yā'),
    ('兔', 'tù'), ('虎', 'hǔ'), ('龙', 'lóng'), ('叶', 'yè'), ('桥', 'qiáo'),
]


def build_chinese():
    qs = []
    for zi, py in HANZI:
        opts = pinyin_distractors(py) + [py]
        opts = list(dict.fromkeys(opts))
        if len(opts) < 4:
            continue
        opts = random.sample(opts, 4) if py in opts else opts
        if py not in opts:
            opts[0] = py
        random.shuffle(opts)
        qs.append({
            'subject': 'chinese', 'type': 'choice', 'level': 1,
            'stem': f'「{zi}」的正确读音是？',
            'options': opts[:4], 'answer': py, 'hint': '',
        })

    # 反向：给拼音选汉字。干扰项必须读音不同，否则题目本身有歧义。
    for zi, py in HANZI:
        others = [z for z, p in HANZI if p != py]
        wrong = random.sample(others, 3)
        opts = wrong + [zi]
        random.shuffle(opts)
        qs.append({
            'subject': 'chinese', 'type': 'choice', 'level': 1,
            'stem': f'读音是 {py} 的字是？',
            'options': opts, 'answer': zi, 'hint': '',
        })
    return qs


# ---------------------------------------------------------------- 数学
def build_math():
    qs = []

    def add_options(ans, lo=0, hi=20):
        cand = {ans}
        for d in (-1, 1, -2, 2, -3, 3, -10, 10):
            v = ans + d
            if lo <= v <= hi and v not in cand:
                cand.add(v)
        pool = [x for x in cand if x != ans]
        random.shuffle(pool)
        opts = pool[:3] + [ans]
        random.shuffle(opts)
        return [str(x) for x in opts]

    for _ in range(34):
        a = random.randint(1, 18)
        b = random.randint(1, 20 - a)
        ans = a + b
        qs.append({
            'subject': 'math', 'type': 'choice', 'level': 1,
            'stem': f'{a} + {b} = ?', 'options': add_options(ans),
            'answer': str(ans), 'hint': '',
        })
    for _ in range(30):
        a = random.randint(2, 20)
        b = random.randint(1, a - 1)
        ans = a - b
        qs.append({
            'subject': 'math', 'type': 'choice', 'level': 1,
            'stem': f'{a} − {b} = ?', 'options': add_options(ans),
            'answer': str(ans), 'hint': '',
        })
    # 填空题：a + ? = c，比纯计算多一步逆向
    for _ in range(16):
        a = random.randint(1, 15)
        b = random.randint(1, 20 - a)
        c = a + b
        qs.append({
            'subject': 'math', 'type': 'choice', 'level': 2,
            'stem': f'{a} + （ ）= {c}',
            'options': add_options(b), 'answer': str(b),
            'hint': f'想一想：{c} 里面去掉 {a} 还剩多少',
        })
    return qs


# ---------------------------------------------------------------- 英语
# 牛津树（Oxford Reading Tree）Stage 1-3 高频词。等级按出现顺序大致对应 1/2/3 阶。
WORDS = [
    ('Kipper', '基珀（人名）', 1), ('Chip', '奇普（人名）', 1), ('Biff', '比夫（人名）', 1),
    ('Floppy', '小狗弗洛皮', 1), ('Mum', '妈妈', 1), ('Dad', '爸爸', 1),
    ('dog', '狗', 1), ('cat', '猫', 1), ('ball', '球', 1), ('tree', '树', 1),
    ('house', '房子', 1), ('car', '汽车', 1), ('cake', '蛋糕', 1), ('water', '水', 1),
    ('milk', '牛奶', 1), ('apple', '苹果', 1), ('teddy', '泰迪熊', 1), ('box', '盒子', 1),
    ('bag', '书包', 1), ('hat', '帽子', 1), ('bed', '床', 1), ('door', '门', 1),
    ('bird', '鸟', 1), ('fish', '鱼', 1), ('duck', '鸭子', 1), ('egg', '鸡蛋', 1),
    ('sun', '太阳', 2), ('rain', '雨', 2), ('snow', '雪', 2), ('park', '公园', 2),
    ('school', '学校', 2), ('shop', '商店', 2), ('garden', '花园', 2), ('flower', '花', 2),
    ('window', '窗户', 2), ('chair', '椅子', 2), ('table', '桌子', 2), ('spoon', '勺子', 2),
    ('cup', '杯子', 2), ('bread', '面包', 2), ('book', '书', 2), ('pen', '钢笔', 2),
    ('swing', '秋千', 2), ('slide', '滑梯', 2), ('frog', '青蛙', 2), ('bath', '洗澡', 2),
    ('big', '大的', 3), ('small', '小的', 3), ('red', '红色', 3), ('blue', '蓝色', 3),
    ('green', '绿色', 3), ('yellow', '黄色', 3), ('look', '看', 3), ('come', '来', 3),
    ('see', '看见', 3), ('jump', '跳', 3), ('play', '玩', 3), ('sleep', '睡觉', 3),
    ('eat', '吃', 3), ('drink', '喝', 3), ('help', '帮忙', 3), ('run', '跑', 3),
]

# 句子选词：只出手写、答案唯一的搭配，避免"语法有争议"被家长质疑
SENTENCES = [
    ('___ is my dad.', ['This', 'These', 'Those', 'They'], 'This', '这是单数，用 This'),
    ('Look ___ Kipper.', ['at', 'in', 'on', 'to'], 'at', 'look at 是固定搭配'),
    ('The cat is ___ the box.', ['in', 'at', 'of', 'to'], 'in', '在盒子里面用 in'),
    ('Mum ___ a big cake.', ['has', 'have', 'is', 'are'], 'has', 'Mum 是第三人称单数'),
    ('I ___ see the dog.', ['can', 'is', 'are', 'am'], 'can', 'can 后面跟动词原形'),
    ('The children ___ in the park.', ['are', 'is', 'am', 'be'], 'are', 'children 是复数'),
    ('This is ___ apple.', ['an', 'a', 'the', 'two'], 'an', 'apple 以元音开头，用 an'),
    ('He is ___ the tree.', ['under', 'of', 'to', 'and'], 'under', '在树下用 under'),
    ('___ the door, please.', ['Open', 'Opens', 'Opening', 'Opened'], 'Open', '祈使句用动词原形'),
    ('The dog ___ big.', ['is', 'are', 'am', 'be'], 'is', 'The dog 是单数'),
    ('We go ___ school.', ['to', 'at', 'in', 'of'], 'to', 'go to + 地点'),
    ('___ are my books.', ['These', 'This', 'It', 'He'], 'These', 'books 是复数'),
    ('Floppy ___ in the garden.', ['runs', 'run', 'running', 'ran'], 'runs', 'Floppy 是单数'),
    ('I like ___ in the park.', ['playing', 'play to', 'plays', 'played to'], 'playing', 'like doing'),
    ('___ a big red ball!', ['What', 'How', 'Who', 'Where'], 'What', '感叹句 What a ...'),
]


def mask_word(w):
    """挖掉 1-2 个字母（不动首尾字母），返回模板"""
    if len(w) <= 3:
        idxs = [1]
    else:
        body = list(range(1, len(w) - 1))
        vowels = [i for i in body if w[i].lower() in 'aeiou']
        idxs = random.sample(vowels, 1) if vowels else []
        if len(w) >= 5 and random.random() < 0.75:
            rest = [i for i in body if i not in idxs]
            if rest:
                idxs.append(random.choice(rest))
        if not idxs:
            idxs = [random.choice(body)]
    tpl = ''.join('_' if i in idxs else c for i, c in enumerate(w))
    return tpl


def build_english():
    qs = []
    glosses = [g for _, g, _ in WORDS]

    for w, g, lv in WORDS:
        others = random.sample([x for x in glosses if x != g], 3)
        opts = others + [g]
        random.shuffle(opts)
        qs.append({
            'subject': 'english', 'type': 'choice', 'level': lv,
            'stem': f'「{w}」是什么意思？', 'options': opts,
            'answer': g, 'hint': '',
        })

    words = [w for w, _, _ in WORDS]
    for w, g, lv in WORDS:
        others = random.sample([x for x in words if x != w], 3)
        opts = others + [w]
        random.shuffle(opts)
        qs.append({
            'subject': 'english', 'type': 'choice', 'level': lv,
            'stem': f'「{g}」用英语怎么说？', 'options': opts,
            'answer': w, 'hint': '',
        })

    for w, g, lv in WORDS:
        if len(w) < 3:
            continue
        qs.append({
            'subject': 'english', 'type': 'spell', 'level': lv,
            'stem': f'补全单词：{g}',
            'tpl': mask_word(w), 'answer': w, 'options': [], 'hint': g,
        })

    for stem, opts, ans, hint in SENTENCES:
        qs.append({
            'subject': 'english', 'type': 'choice', 'level': 3,
            'stem': stem, 'options': opts, 'answer': ans, 'hint': hint,
        })
    return qs


def main():
    banks = [
        {
            'name': '语文 · 拼音认读（一年级）',
            'subject': 'chinese',
            'description': '给汉字选读音、给读音选汉字，覆盖 70 个常用字',
            'questions': build_chinese(),
        },
        {
            'name': '数学 · 20 以内加减',
            'subject': 'math',
            'description': '20 以内加法、减法与填空题，含简单逆向思维',
            'questions': build_math(),
        },
        {
            'name': '英语 · 牛津树 1-3 级',
            'subject': 'english',
            'description': '牛津树 1-3 阶高频词：英中互译、补全单词、句子选词',
            'questions': build_english(),
        },
    ]
    data = {'version': 1, 'banks': banks}
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, '..', 'static', 'quiz_seed.json')
    out = os.path.normpath(out)
    with io.open(out, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    total = sum(len(b['questions']) for b in banks)
    print(f'已生成 {out}：{len(banks)} 套题库、{total} 道题')
    for b in banks:
        print(f"  · {b['name']}  {len(b['questions'])} 题")

    # 抽样自查：打印前几题，人工确认拼音与选项合理
    print('\n抽样（语文拼音，前 4 题）：')
    for q in banks[0]['questions'][:4]:
        print('   ', q['stem'], q['options'], '→', q['answer'])
    print('抽样（英语补全单词，前 3 题）：')
    for q in banks[2]['questions']:
        if q['type'] == 'spell':
            print('   ', q['stem'], q['tpl'], '→', q['answer'])
            if q['answer'] == 'yellow':
                break


if __name__ == '__main__':
    main()
