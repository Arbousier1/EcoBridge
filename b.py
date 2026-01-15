import os
import re

def smart_format_java(file_path, indent_size=4):
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()

        new_lines = []
        indent_level = 0
        in_block_comment = False

        for line in lines:
            trimmed = line.strip()

            # 1. 处理空行
            if not trimmed:
                new_lines.append("\n")
                continue

            # 2. 处理多行注释块 /* ... */
            if in_block_comment:
                if '*/' in trimmed:
                    in_block_comment = False
                # 注释块内部保持 1 个空格的偏移或原有相对缩进
                new_lines.append(" " * (indent_level * indent_size + 1) + trimmed + "\n")
                continue
            if trimmed.startswith('/*'):
                if '*/' not in trimmed:
                    in_block_comment = True
                new_lines.append(" " * (indent_level * indent_size) + trimmed + "\n")
                continue

            # 3. 预处理：移除字符串和单行注释，用于统计大括号
            # 防止 String s = " { "; 这种干扰判断
            clean_line = re.sub(r'".*?"', '', trimmed) # 移除双引号字符串
            clean_line = re.sub(r'//.*', '', clean_line) # 移除单行注释

            # 4. 计算当前行的缩进修正
            # 如果行首是 '}' 或 'case' 或 'default'，该行需要临时减小缩进
            outdent_this_line = (
                trimmed.startswith('}') or 
                trimmed.startswith('case ') or 
                trimmed.startswith('default:') or
                trimmed.startswith(')') # 处理多行参数收尾
            )

            current_indent = indent_level - (1 if outdent_this_line else 0)
            
            # 5. 生成格式化行
            final_line = (" " * max(0, current_indent * indent_size)) + trimmed + "\n"
            new_lines.append(final_line)

            # 6. 为下一行计算嵌套层级
            net_change = clean_line.count('{') - clean_line.count('}')
            
            # 针对 switch-case 的特殊处理：case 增加一层，直到遇到 break/case/default
            if trimmed.startswith('case ') or trimmed.startswith('default:'):
                if '{' not in trimmed: # 如果 case 没带大括号，手动加一层
                    indent_level += 1
            
            # 如果这一行有 break 且之前是 case 层级，则退回一层
            if (trimmed.startswith('break;') or trimmed.startswith('return ')) and not '{' in clean_line:
                # 这里是一个简单的启发式逻辑：如果在 case 分支内
                pass # 实际复杂逻辑建议配合 IDE

            indent_level += net_change

        with open(file_path, 'w', encoding='utf-8') as f:
            f.writelines(new_lines)
        print(f"✨ 智能重构完成: {file_path}")

    except Exception as e:
        print(f"❌ 出错: {file_path} -> {e}")

def run(path):
    if os.path.isdir(path):
        for root, _, files in os.walk(path):
            for f in files:
                if f.endswith('.java'):
                    smart_format_java(os.path.join(root, f))
    elif os.path.isfile(path):
        smart_format_java(path)

if __name__ == "__main__":
    p = input("请输入 Java 文件或文件夹路径: ").strip()
    run(p)