#!/usr/bin/env python3
"""Generates the town's achievements: the game's own advancements, in a Stad tab. Colony/Achievements.java awards them.

    advancements.py <resources dir>      # mod/src/main/resources
"""
import json, os, sys

# id, parent, icon, title, description, frame
ALL = [
    ("root", None, "stad:town_hall", "Stad", "Found a town: place a town hall", "task"),
    ("first_citizen", "root", "minecraft:red_bed", "Someone Moves In", "A newcomer settles in your town", "task"),
    ("first_job", "first_citizen", "minecraft:wooden_shovel", "Put to Work", "Give a citizen a job", "task"),
    ("first_building", "first_job", "minecraft:oak_door", "Raising the Roof", "Your builder finishes a first building", "task"),
    ("homes", "first_building", "minecraft:white_bed", "A Roof for Everyone", "Five citizens or more, and a home for every one of them", "task"),
    ("road", "first_building", "minecraft:dirt_path", "All Roads Lead Home", "Build a road", "task"),
    ("warehouse", "first_building", "minecraft:barrel", "A Place for Everything", "A warehouse, and a courier to carry the goods", "task"),
    ("trade", "warehouse", "minecraft:emerald", "Open for Business", "Trade with your town's merchant", "task"),
    ("research", "first_building", "minecraft:lectern", "Bright Ideas", "Your university finishes researching a topic", "task"),
    ("all_research", "research", "minecraft:enchanted_book", "Learned Them All", "Research every topic", "challenge"),
    ("fed", "first_job", "minecraft:bread", "Bread on the Table", "A farmer and a cook to keep the town fed", "task"),
    ("jobs", "first_job", "minecraft:crafting_table", "Many Hands", "Ten different jobs in one town", "goal"),
    ("diamonds", "first_job", "minecraft:diamond", "Diamonds!", "Your miners dig up diamonds", "task"),
    ("bedrock", "first_job", "minecraft:bedrock", "Rock Bottom", "Your miners dig down to bedrock", "goal"),
    ("child", "first_citizen", "minecraft:cake", "The Next Generation", "A child is born in your town", "task"),
    ("village", "first_citizen", "minecraft:bell", "A Village", "Ten citizens", "goal"),
    ("town", "village", "minecraft:lantern", "A Proper Town", "Twenty-five citizens", "goal"),
    ("city", "town", "minecraft:beacon", "City Lights", "Fifty citizens", "challenge"),
    ("land", "town", "minecraft:filled_map", "As Far as the Eye Can See", "Your town's land grows as far as it goes", "goal"),
    ("raid", "root", "minecraft:shield", "Beaten Off", "See off a raid on your town", "task"),
    ("war", "raid", "minecraft:iron_sword", "To the Victor", "Win a war against another town", "challenge"),
]

def main(resources):
    out = os.path.join(resources, "data", "stad", "advancement")
    os.makedirs(out, exist_ok=True)
    for name in os.listdir(out):
        if name.endswith(".json"):
            os.remove(os.path.join(out, name))
    for id_, parent, icon, title, desc, frame in ALL:
        display = {"icon": {"id": icon}, "title": {"text": title}, "description": {"text": desc}, "frame": frame,
                   "show_toast": True, "announce_to_chat": parent is not None, "hidden": False}
        a = {}
        if parent:
            a["parent"] = "stad:" + parent
        else:
            display["background"] = "minecraft:textures/block/spruce_planks.png"
        a["display"] = display
        a["criteria"] = {"done": {"trigger": "minecraft:impossible"}}
        with open(os.path.join(out, id_ + ".json"), "w") as f:
            json.dump(a, f, indent=2)
            f.write("\n")
    print(len(ALL), "achievements")

if __name__ == "__main__":
    main(sys.argv[1])
