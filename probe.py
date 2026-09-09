"""连接手机，dump 出 Soul 当前界面的控件结构，用来找定位用的 id/text。"""

import uiautomator2 as u2

d = u2.connect()
print("设备信息:", d.info)

xml = d.dump_hierarchy()
with open("hierarchy.xml", "w", encoding="utf-8") as f:
    f.write(xml)
print("已保存完整控件树到 hierarchy.xml")

print("\n当前界面元素（resource-id | text | class | bounds）:")
for el in d.xpath("//*").all():
    a = el.attrib
    rid = a.get("resource-id", "")
    text = a.get("text", "")
    cls = a.get("class", "")
    bounds = a.get("bounds", "")
    if rid or text:
        print(f"{rid} | {text} | {cls} | {bounds}")
