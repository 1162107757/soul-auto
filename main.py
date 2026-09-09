import datetime
import random
import re
import sys
import time

import uiautomator2 as u2
from openai import OpenAI

import config

if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# ---------- Soul 控件定位 ----------
INPUT_BOX = '//*[@resource-id="cn.soulapp.android:id/et_sendmessage"]'
CONTENT_TEXT = (
    '//*[@resource-id="cn.soulapp.android:id/list_conversation"]'
    '//*[@resource-id="cn.soulapp.android:id/content_text"]'
)
SEND_BUTTON = '//*[@resource-id="cn.soulapp.android:id/btn_send"]'
CHAT_ITEM = '//*[@resource-id="cn.soulapp.android:id/item_root"]'
CHAT_AVATAR = '//*[@resource-id="cn.soulapp.android:id/chat_avatar"]'
CONV_LIST = '//*[@resource-id="cn.soulapp.android:id/conversation_list"]'
CONV_ITEM = '//*[@resource-id="cn.soulapp.android:id/item_content_root"]'
CONV_NAME = '//*[@resource-id="cn.soulapp.android:id/name"]'
CONV_TIME = '//*[@resource-id="cn.soulapp.android:id/time"]'
UNREAD_BADGE = '//*[@resource-id="cn.soulapp.android:id/unread_msg_number"]'
SQUARE_TAB = '//*[@resource-id="cn.soulapp.android:id/main_tab_square"]'
MSG_TAB = '//*[@resource-id="cn.soulapp.android:id/main_tab_msg"]'
SQUARE_CARD = '//*[@resource-id="cn.soulapp.android:id/llSquare"]'
SQUARE_TEXT = '//*[@resource-id="cn.soulapp.android:id/square_item_text"]'
SQUARE_AVATAR = '//*[@resource-id="cn.soulapp.android:id/flAvatar"]'
SQUARE_PAGER = '//*[@resource-id="cn.soulapp.android:id/pager_square"]'
GIFT_CLOSE = '//*[@resource-id="cn.soulapp.android:id/tv_btn_close"]'
CHAT_SECRET = '//*[@resource-id="cn.soulapp.android:id/tv_chat_secret"]'
PROFILE_NAME = '//*[@resource-id="cn.soulapp.android:id/titlebar_text_tv"]'

CONTEXT_LEN = 12   # 喂给 LLM 的最近消息条数
DM_INTERVAL = 240  # 广场私信间隔（秒），保守约 4 分钟
REPLY_CHAT = False # 是否回复聊天列表消息；调试广场打招呼时先关掉

WEEKDAYS = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]


def build_system_prompt():
    now = datetime.datetime.now()
    date_str = f"{now.year}年{now.month}月{now.day}日 {WEEKDAYS[now.weekday()]}"
    return (
        f"你在 Soul（社交App）上和一个刚认识的人聊天。今天真实日期是 {date_str}，"
        f"现在是 {now.hour} 点左右。\n"
        "用真实自然的口语回复，像普通人随手打字：\n"
        "- 句子简短、口语化，别写工整完整的长句\n"
        "- 带点语气词（哈、呀、啦、呢），表情用真正的 emoji（😄😂😅）\n"
        "- 千万别把表情写成（叹气）（笑）（哭）这种文字括号\n"
        "- 别每条都反问、别每条都附和，偶尔简单应一句\n"
        "- 别用「你好」「很高兴认识你」这类正式客套\n"
        "- 提到时间就用上面的真实日期，别乱说周末或节日\n"
        "- 别暴露自己是AI或机器人"
    )


d = u2.connect()

llm = OpenAI(api_key=config.DEEPSEEK_API_KEY, base_url=config.DEEPSEEK_BASE_URL)


def _nums(s):
    return [int(n) for n in re.findall(r"-?\d+", s)]


def _bounds(el):
    nums = _nums(el.attrib.get("bounds", ""))
    return tuple(nums) if len(nums) == 4 else None


def _contains(outer, inner):
    if not outer or not inner:
        return False
    lo, to, ro, bo = outer
    li, ti, ri, bi = inner
    return lo <= li and to <= ti and ro >= ri and bo >= bi


def think_delay():
    """期望在对方消息发出后多久回复（秒）：大部分 1 分钟内，偶尔稍长。"""
    r = random.random()
    if r < 0.6:
        return random.uniform(15, 50)      # 60% 快回（1 分钟内）
    if r < 0.9:
        return random.uniform(50, 90)      # 30% 中等（约 1 分钟）
    return random.uniform(90, 180)         # 10% 稍慢（1.5-3 分钟）


def parse_minutes_ago(s):
    """把会话右侧的时间字符串粗略解析成「几分钟前」。"""
    s = (s or "").strip()
    if not s or s in ("刚刚", "现在"):
        return 0
    m = re.match(r"(\d+)\s*分钟前", s)
    if m:
        return int(m.group(1))
    m = re.match(r"(\d+)\s*小时前", s)
    if m:
        return int(m.group(1)) * 60
    m = re.match(r"(\d+)\s*天前", s)
    if m:
        return int(m.group(1)) * 24 * 60
    if s == "昨天":
        return 24 * 60
    m = re.match(r"(?:今天\s*)?(\d{1,2}):(\d{2})", s)
    if m:
        hh, mm = int(m.group(1)), int(m.group(2))
        now = datetime.datetime.now()
        minutes = (now.hour - hh) * 60 + (now.minute - mm)
        if minutes < 0:
            minutes += 24 * 60
        return minutes
    return 24 * 60   # 其它格式（星期几/日期），当作较久之前


def reply_delay(time_str):
    """根据对方消息已发出多久，计算还需要等多久（秒）。"""
    minutes_ago = parse_minutes_ago(time_str)
    return max(0, think_delay() - minutes_ago * 60)


def is_on_chat():
    return bool(d.xpath(INPUT_BOX).all())


def is_on_list():
    return bool(d.xpath(CONV_LIST).all())


def is_on_square():
    return bool(d.xpath(SQUARE_PAGER).all())


def find_conversation(name):
    """在会话列表里按名字找会话项，返回 item 或 None。"""
    items = d.xpath(CONV_ITEM).all()
    names = d.xpath(CONV_NAME).all()
    for item in items:
        ib = _bounds(item)
        for n in names:
            if _contains(ib, _bounds(n)) and n.attrib.get("text", "") == name:
                return item
    return None


def scan_unread():
    """扫描会话列表，返回有未读角标的会话 [(name, time_str), ...]。"""
    items = d.xpath(CONV_ITEM).all()
    names = d.xpath(CONV_NAME).all()
    times = d.xpath(CONV_TIME).all()
    badges = d.xpath(UNREAD_BADGE).all()
    result = []
    for item in items:
        ib = _bounds(item)
        if not any(_contains(ib, _bounds(b)) for b in badges):
            continue
        name = ""
        for n in names:
            if _contains(ib, _bounds(n)):
                name = n.attrib.get("text", "")
                break
        time_str = ""
        for t in times:
            if _contains(ib, _bounds(t)):
                time_str = t.attrib.get("text", "")
                break
        result.append((name, time_str))
    return result


def read_thread():
    """当前屏幕可见消息（含双方），按时间顺序，[{'role': 'in'/'out', 'text': ...}]。"""
    items = d.xpath(CHAT_ITEM).all()
    avatars = d.xpath(CHAT_AVATAR).all()
    texts = d.xpath(CONTENT_TEXT).all()
    result = []
    for item in items:
        ib = _bounds(item)
        text = ""
        for t in texts:
            if _contains(ib, _bounds(t)):
                text = t.attrib.get("text", "")
                break
        if not text:
            continue
        # 用头像位置判断左右：对方头像靠左，自己头像靠右（不受消息长短影响）
        role = "in"
        for av in avatars:
            ab = _bounds(av)
            if _contains(ib, ab):
                role = "out" if ab[0] > 540 else "in"
                break
        result.append({"role": role, "text": text})
    return result


def reply_to(name, seen):
    """进入指定会话，若对方最后发言且是没回过的就回复。返回是否回复了。"""
    item = find_conversation(name)
    if not item:
        return False
    item.click()
    if not d.xpath(INPUT_BOX).wait(timeout=5):
        d.press("back")
        time.sleep(1.5)
        return False
    thread = read_thread()
    if not thread or thread[-1]["role"] != "in":
        d.press("back")
        time.sleep(1.5)
        return False
    incoming = [m for m in thread if m["role"] == "in"]
    s = seen.setdefault(name, set())
    new = [m["text"] for m in incoming if m["text"] not in s]
    if not new:
        d.press("back")
        time.sleep(1.5)
        return False
    s.update(m["text"] for m in incoming)
    reply = ask_llm(thread)
    send(reply)
    print(f"[{name}] 回复: {incoming[-1]['text']} -> {reply}", flush=True)
    d.press("back")
    time.sleep(1.5)
    return True


def ask_llm(thread):
    recent = thread[-CONTEXT_LEN:]
    messages = [{"role": "system", "content": build_system_prompt()}]
    for m in recent:
        role = "user" if m["role"] == "in" else "assistant"
        messages.append({"role": role, "content": m["text"]})
    resp = llm.chat.completions.create(
        model=config.DEEPSEEK_MODEL,
        messages=messages,
    )
    return resp.choices[0].message.content.strip()


def split_reply(text):
    """把回复按句子拆开（最多 3 条），多句就分开发。"""
    text = text.strip()
    parts = re.split(r"(?<=[。！？!?…~])", text)
    parts = [p.strip() for p in parts if p.strip()]
    if len(parts) <= 1:
        return [text]
    return parts[:3]


def send(text):
    pieces = split_reply(text)
    for i, piece in enumerate(pieces):
        d.xpath(INPUT_BOX).click()
        time.sleep(0.3)
        d.send_keys(piece, clear=True)
        time.sleep(max(1.0, len(piece) * random.uniform(0.25, 0.45)))   # 模拟打字耗时
        btn = d.xpath(SEND_BUTTON)
        if btn.wait(timeout=3):
            btn.click()
        else:
            d.press("enter")
        if i < len(pieces) - 1:
            time.sleep(random.uniform(2, 5))   # 两条消息之间的间隔


def goto_square():
    d.xpath(SQUARE_TAB).click()
    time.sleep(1.5)


def goto_chat_list():
    for _ in range(3):
        if d.xpath(MSG_TAB).exists:
            d.xpath(MSG_TAB).click()
            time.sleep(1.5)
            return
        d.press("back")   # 可能在详情/弹窗里，先返回
        time.sleep(1.0)


def read_posts():
    """返回可见的非广告帖子 [(text, avatar_btn), ...]，按顺序。"""
    cards = d.xpath(SQUARE_CARD).all()
    texts = d.xpath(SQUARE_TEXT).all()
    avatars = d.xpath(SQUARE_AVATAR).all()
    result = []
    for card in cards:
        cb = _bounds(card)
        text = ""
        for t in texts:
            if _contains(cb, _bounds(t)):
                text = t.attrib.get("text", "")
                break
        avatar = None
        for a in avatars:
            if _contains(cb, _bounds(a)):
                avatar = a
                break
        if len(text.strip()) >= 2 and avatar:   # 跳过纯图片/文字过短的帖子
            result.append((text, avatar))
    return result


def get_profile_name():
    els = d.xpath(PROFILE_NAME).all()
    return els[0].attrib.get("text", "") if els else ""


def back_to_square():
    """按返回直到回到广场列表。"""
    for _ in range(4):
        if d.xpath(SQUARE_CARD).exists:
            return True
        d.press("back")
        time.sleep(1.0)
    return bool(d.xpath(SQUARE_CARD).exists)


def skip_gift():
    d.xpath(GIFT_CLOSE).click()
    time.sleep(1.5)


def generate_dm_reply(post_text):
    messages = [
        {
            "role": "system",
            "content": build_system_prompt() + " 现在要根据对方发的一条广场动态，写一句自然的私聊开场白，能接上这条动态。",
        },
        {"role": "user", "content": f"对方发的广场动态：{post_text}\n写一句自然、口语化、简短的私聊开场白。"},
    ]
    resp = llm.chat.completions.create(model=config.DEEPSEEK_MODEL, messages=messages)
    return resp.choices[0].message.content.strip()


def browse_square_once(seen_dm, greeted):
    """点头像进主页→点私聊，跳过送礼的和已经打过招呼的；私聊完继续下滑翻更多。"""
    for _ in range(6):   # 最多翻 6 屏
        posts = read_posts()
        for text, avatar_btn in posts:
            if text in seen_dm:      # 已试过/私聊过的跳过
                continue
            seen_dm.add(text)        # 标记已尝试，避免下滑后重复
            avatar_btn.click()       # 点头像进主页
            time.sleep(2.0)
            name = get_profile_name()
            if name and name in greeted:   # 打过招呼的跳过
                back_to_square()
                continue
            if not d.xpath(CHAT_SECRET).exists:
                back_to_square()
                continue
            d.xpath(CHAT_SECRET).click()   # 主页点私聊
            time.sleep(2.5)
            if d.xpath(GIFT_CLOSE).exists:   # 要送礼，跳过
                skip_gift()
                back_to_square()
                continue
            if d.xpath(INPUT_BOX).exists:    # 免费进聊天，发开场白
                reply = generate_dm_reply(text)
                send(reply)
                if name:
                    greeted.add(name)        # 标记已打过招呼
                print(f"[广场] 私聊 {name}：{text[:20]}... -> {reply}", flush=True)
                back_to_square()
                return True
            back_to_square()
        # 这一屏试完了，向上滑动加载更多帖子
        d.swipe(540, 1800, 540, 600, duration=0.5)
        time.sleep(1.5)
    return False


def main():
    seen = {}       # 会话名 -> 已回复过的对方消息集合
    seen_dm = set() # 已私聊过的广场帖子文本
    greeted = set() # 已打过招呼的用户名
    last_dm = 0
    print("启动完成，监听会话列表...", flush=True)
    if is_on_chat():
        d.press("back")
        time.sleep(1.5)
    while True:
        try:
            if not REPLY_CHAT:
                # 测试模式：一直待在广场刷打招呼，不回聊天
                if is_on_chat():
                    d.press("back")
                    time.sleep(1.5)
                    continue
                if not is_on_square():
                    goto_square()
                    continue
                if time.time() - last_dm >= DM_INTERVAL:
                    browse_square_once(seen_dm, greeted)
                    last_dm = time.time()
                else:
                    time.sleep(random.uniform(3, 5))
                continue

            # 正常模式：优先回聊天未读，空闲时刷广场
            if is_on_chat():               # 在聊天里先退回列表
                d.press("back")
                time.sleep(1.5)
                continue
            if not is_on_list():           # 不在列表就切回聊天 tab
                goto_chat_list()
                continue

            unread = scan_unread()
            if unread:
                name, time_str = unread[0]
                delay = reply_delay(time_str)  # 在列表上等，到点再进去
                if delay > 0:
                    time.sleep(delay)
                reply_to(name, seen)
                continue

            # 无未读：间隔够了就去广场刷私信
            if time.time() - last_dm >= DM_INTERVAL:
                goto_square()
                browse_square_once(seen_dm, greeted)
                goto_chat_list()
                last_dm = time.time()
            else:
                time.sleep(random.uniform(3, 5))
        except Exception as e:
            print("出错:", e, flush=True)
            try:
                d.press("back")
            except Exception:
                pass
            time.sleep(2)


if __name__ == "__main__":
    main()
