import os
import re

def clean_code(content, ext):
    """
    移除注释并合并空格/空行
    """
    # 1. 处理多行注释 (针对 Java 和 Rust 的 /* ... */)
    if ext in ['.java', '.rs']:
        content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)

    lines = content.splitlines()
    cleaned_lines = []

    for line in lines:
        # 2. 处理单行注释
        if ext in ['.java', '.rs']:
            line = re.sub(r'//.*', '', line)
        elif ext == '.toml':
            line = re.sub(r'#.*', '', line)
        
        # 3. 移除行首行尾空格
        line = line.strip()
        
        # 4. 只有非空行才保留
        if line:
            # 替换多个空格为单个空格 (可选，如果需要极度压缩可以开启)
            # line = re.sub(r'\s+', ' ', line)
            cleaned_lines.append(line)

    return "\n".join(cleaned_lines)

def export_project(source_dir, output_file):
    target_exts = ['.rs', '.java', '.toml', '.kts']
    
    with open(output_file, 'w', encoding='utf-8') as f_out:
        for root, dirs, files in os.walk(source_dir):
            # 排除常见的忽略目录
            if any(part in root for part in ['target', 'build', '.git', 'gradle', 'yml']):
                continue
                
            for file in files:
                ext = os.path.splitext(file)[1].lower()
                if ext in target_exts:
                    file_path = os.path.join(root, file)
                    rel_path = os.path.relpath(file_path, source_dir)
                    
                    try:
                        with open(file_path, 'r', encoding='utf-8') as f_in:
                            raw_content = f_in.read()
                            
                        processed_code = clean_code(raw_content, ext)
                        
                        # 写入文件标记，方便区分
                        f_out.write(f"\n{'='*50}\n")
                        f_out.write(f"FILE: {rel_path}\n")
                        f_out.write(f"{'='*50}\n\n")
                        f_out.write(processed_code)
                        f_out.write("\n")
                        
                        print(f"已处理: {rel_path}")
                    except Exception as e:
                        print(f"无法读取 {file_path}: {e}")

if __name__ == "__main__":
    # 配置区
    SOURCE_DIRECTORY = "./"  # 你的项目根目录
    OUTPUT_FILENAME = "exported_code.txt"
    
    print(f"正在导出代码至 {OUTPUT_FILENAME}...")
    export_project(SOURCE_DIRECTORY, OUTPUT_FILENAME)
    print("\n完成！所有处理后的代码已保存在 exported_code.txt 中。")