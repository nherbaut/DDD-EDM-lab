import os

from huggingface_hub import snapshot_download


MODEL_REPO = os.getenv("MODEL_REPO", "serbekun/CCAiM")
MODEL_DIR = os.getenv("MODEL_DIR", "models/cloud-classifier")
HF_TOKEN = os.getenv("HF_TOKEN")


def main() -> None:
    os.makedirs(MODEL_DIR, exist_ok=True)
    snapshot_download(
        repo_id=MODEL_REPO,
        local_dir=MODEL_DIR,
        token=HF_TOKEN,
    )
    print(f"Model downloaded from {MODEL_REPO} to {MODEL_DIR}")


if __name__ == "__main__":
    main()
