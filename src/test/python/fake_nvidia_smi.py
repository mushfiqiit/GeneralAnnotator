"""A stand-in for ``nvidia-smi --query-gpu=name,memory.total --format=csv,noheader,nounits``."""
import os

print(f"NVIDIA A100-SXM4-80GB, {os.environ.get('FAKE_GPU_MEMORY_MIB', '81920')}")
