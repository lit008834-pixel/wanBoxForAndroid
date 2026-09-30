#!/usr/bin/env python3
# @author 雾晚
"""清洗 sing-box JSON outbounds 或 Mihomo JSON proxies；不联网、不解析任意代码。"""
import argparse
import copy
import hashlib
import json
import re
import unicodedata
from pathlib import Path

NOTICE = re.compile(r"套餐到期|剩余流量|官网|防失联|通知|重置|过期时间|到期时间|流量剩余|订阅到期", re.I)


def clean_name(name):
    text = "".join(
        " " if unicodedata.category(c) in {"So", "Cf"} or c in "\ufe0e\ufe0f\u20e3" else c
        for c in name
    )
    return re.sub(r"\s+", " ", text).strip().strip("·•| ")


def identity(node):
    value = copy.deepcopy(node)
    value.pop("name", None)
    value.pop("tag", None)
    if isinstance(value.get("server"), str):
        value["server"] = value["server"].strip().lower()
    # 全量协议参数防止同端口、不同密码/SNI/transport 的有效节点被误删。
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                    separators=(",", ":")).encode()).hexdigest()


def clean(document):
    result = copy.deepcopy(document)
    key = "outbounds" if "outbounds" in result else "proxies"
    if not isinstance(result.get(key), list):
        raise ValueError("需要 outbounds 或 proxies 数组（YAML 请先转为 JSON）")
    label = "tag" if key == "outbounds" else "name"
    seen, mapping, nodes, used = {}, {}, [], set()
    # 仅清洗有服务器的真实节点，保留 direct、selector、urltest 等结构节点。
    reserved = {n.get(label) for n in result[key] if "server" not in n}
    used.update(reserved)
    for node in result[key]:
        old = node.get(label, "")
        if "server" not in node:
            nodes.append(node)
            continue
        if NOTICE.search(old):
            mapping[old] = None
            continue
        digest = identity(node)
        if digest in seen:
            mapping[old] = seen[digest]
            continue
        base = clean_name(old) or f"{node.get('type', 'proxy')}-{len(seen)+1}"
        name, number = base, 2
        while name in used:
            name, number = f"{base} ({number})", number + 1
        used.add(name)
        node[label] = name
        mapping[old] = name
        seen[digest] = name
        nodes.append(node)
    result[key] = nodes
    # 修复选择器、测速组及 detour/route 引用，禁止产生空组或静默改为直连。
    def references(value):
        if isinstance(value, dict):
            for field, item in list(value.items()):
                if field in {"outbounds", "proxies"} and isinstance(item, list) and all(isinstance(x, str) for x in item):
                    value[field] = list(dict.fromkeys(mapping.get(x, x) for x in item if mapping.get(x, x)))
                    if item and not value[field]:
                        raise ValueError("清洗后代理组为空，请手动选择有效节点")
                elif field in {"outbound", "detour", "final", "default"} and isinstance(item, str) and item in mapping:
                    if mapping[item] is None:
                        raise ValueError(f"{field} 引用了被过滤的节点，请先更换引用")
                    value[field] = mapping[item]
                else:
                    references(item)
        elif isinstance(value, list):
            for item in value:
                references(item)
    references(result)
    # Mihomo 文本规则不是 sing-box JSON 路由对象，拒绝静默破坏规则目标。
    for rule in result.get("rules", []):
        if isinstance(rule, str):
            parts = rule.split(",")
            target = -2 if parts[-1] == "no-resolve" else -1
            old = parts[target]
            if old in mapping:
                if mapping[old] is None:
                    raise ValueError("规则引用已过滤节点，请手动修改规则")
                parts[target] = mapping[old]
                result["rules"][result["rules"].index(rule)] = ",".join(parts)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error("输出必须使用新文件，保留原始订阅备份")
    result = clean(json.loads(args.input.read_text(encoding="utf-8-sig")))
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")


if __name__ == "__main__":
    main()
