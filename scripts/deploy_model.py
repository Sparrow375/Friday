import os
import sys
import json
import zipfile
import shutil

ASSETS_DIR = "f:/Avaneesh/projects/Friday/app/src/main/assets"
DOWNLOADS_DIR = os.path.expanduser("~/Downloads")

def find_latest_model():
    candidates = []
    # Search Downloads
    if os.path.exists(DOWNLOADS_DIR):
        for f in os.listdir(DOWNLOADS_DIR):
            if "joint_nlu" in f.lower() and f.endswith((".zip", ".onnx")):
                p = os.path.join(DOWNLOADS_DIR, f)
                candidates.append((p, os.path.getmtime(p)))
    
    # Search Project output
    output_dir = "f:/Avaneesh/projects/Friday/output"
    if os.path.exists(output_dir):
        for root, _, files in os.walk(output_dir):
            for f in files:
                if "joint_nlu_model.onnx" in f or "friday_joint_nlu.zip" in f:
                    p = os.path.join(root, f)
                    candidates.append((p, os.path.getmtime(p)))
                    
    candidates.sort(key=lambda x: x[1], reverse=True)
    return candidates

def deploy_from_zip(zip_path):
    print(f"Deploying from {zip_path}...")
    with zipfile.ZipFile(zip_path, 'r') as z:
        for member in z.namelist():
            filename = os.path.basename(member)
            if filename in ["joint_nlu_model.onnx", "joint_intent_labels.json", "joint_slot_labels.json"]:
                target_path = os.path.join(ASSETS_DIR, filename)
                with z.open(member) as source, open(target_path, "wb") as target:
                    shutil.copyfileobj(source, target)
                print(f"Extracted {filename} -> {target_path} ({os.path.getsize(target_path)} bytes)")

    update_text_labels()

def deploy_from_files(model_onnx_path, intent_json_path=None, slot_json_path=None):
    dest_model = os.path.join(ASSETS_DIR, "joint_nlu_model.onnx")
    shutil.copy2(model_onnx_path, dest_model)
    print(f"Copied model -> {dest_model} ({os.path.getsize(dest_model)} bytes)")
    
    src_dir = os.path.dirname(model_onnx_path)
    if not intent_json_path:
        candidate_intent = os.path.join(src_dir, "joint_intent_labels.json")
        if os.path.exists(candidate_intent):
            intent_json_path = candidate_intent
    if not slot_json_path:
        candidate_slot = os.path.join(src_dir, "joint_slot_labels.json")
        if os.path.exists(candidate_slot):
            slot_json_path = candidate_slot

    if intent_json_path and os.path.exists(intent_json_path):
        shutil.copy2(intent_json_path, os.path.join(ASSETS_DIR, "joint_intent_labels.json"))
        print(f"Copied {intent_json_path} -> {os.path.join(ASSETS_DIR, 'joint_intent_labels.json')}")
    if slot_json_path and os.path.exists(slot_json_path):
        shutil.copy2(slot_json_path, os.path.join(ASSETS_DIR, "joint_slot_labels.json"))
        print(f"Copied {slot_json_path} -> {os.path.join(ASSETS_DIR, 'joint_slot_labels.json')}")
        
    update_text_labels()

def update_text_labels():
    intent_json = os.path.join(ASSETS_DIR, "joint_intent_labels.json")
    if os.path.exists(intent_json):
        with open(intent_json, "r", encoding="utf-8") as f:
            intents = json.load(f)
        labels_txt = os.path.join(ASSETS_DIR, "labels.txt")
        with open(labels_txt, "w", encoding="utf-8") as f:
            for item in intents:
                f.write(f"{item}\n")
        print(f"Updated {labels_txt} with {len(intents)} intents.")

    slot_json = os.path.join(ASSETS_DIR, "joint_slot_labels.json")
    if os.path.exists(slot_json):
        with open(slot_json, "r", encoding="utf-8") as f:
            slots = json.load(f)
        slots_txt = os.path.join(ASSETS_DIR, "slot_labels.txt")
        with open(slots_txt, "w", encoding="utf-8") as f:
            for item in slots:
                f.write(f"{item}\n")
        print(f"Updated {slots_txt} with {len(slots)} slot labels.")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        target = sys.argv[1]
        if target.endswith(".zip"):
            deploy_from_zip(target)
        elif target.endswith(".onnx"):
            deploy_from_files(target)
    else:
        found = find_latest_model()
        if found:
            print("Found candidates:")
            for p, mtime in found:
                print(f" - {p}")
        else:
            print("No model files found automatically in Downloads or output/.")
