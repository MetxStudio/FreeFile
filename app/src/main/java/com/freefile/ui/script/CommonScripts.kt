package com.freefile.ui.script

import com.freefile.R

/**
 * 固定常用脚本（用户反馈 2026-09-05）：内置一组常用 Lua 脚本模板，
 * 用户在脚本页可一键载入到编辑器，避免每次从零开始编写。
 *
 * 全部使用 [com.freefile.script.LuaEngine] 提供的 freefile.* API：
 *   freefile.list / read / write / copy / delete / mkdir / rename / exists / log
 *
 * 字段：
 *  - [nameRes]：显示名（已外化到 strings.xml）
 *  - [code]：脚本正文（含注释，便于用户读懂后二次修改）
 */
data class CommonScript(
    val nameRes: Int,
    val code: String
)

/**
 * 内置常用脚本列表。顺序即展示顺序。
 */
val CommonScripts: List<CommonScript> = listOf(
    CommonScript(
        nameRes = R.string.script_common_list_dir,
        code = """-- 列出指定目录下的文件与目录
-- 修改 path 变量为你想查看的目录
local path = '/storage/emulated/0'
freefile.log('目录: ' .. path)
freefile.log('-------------------')
local items = freefile.list(path) or {}
for i, f in ipairs(items) do
  local tag = f.isdir and '[D]' or ('[' .. (f.size or 0) .. 'B]')
  freefile.log(string.format('%-12s %s', tag, f.name))
end
freefile.log('-------------------')
freefile.log('共 ' .. #items .. ' 项')
"""
    ),
    CommonScript(
        nameRes = R.string.script_common_storage_info,
        code = """-- 显示内置存储的统计信息
local root = '/storage/emulated/0'
local items = freefile.list(root) or {}
local totalFiles = 0
local totalDirs = 0
local totalSize = 0
for _, f in ipairs(items) do
  if f.isdir then
    totalDirs = totalDirs + 1
  else
    totalFiles = totalFiles + 1
    totalSize = totalSize + (f.size or 0)
  end
end
freefile.log('存储: ' .. root)
freefile.log('一级目录数: ' .. totalDirs)
freefile.log('一级文件数: ' .. totalFiles)
freefile.log('一级文件总大小: ' .. totalSize .. ' B (' ..
  string.format('%.2f MB', totalSize / 1024 / 1024) .. ')')
"""
    ),
    CommonScript(
        nameRes = R.string.script_common_find_large,
        code = """-- 查找指定目录下大于阈值（默认 10MB）的文件
-- maxDepth 控制递归深度，防止过深扫描卡死
local root = '/storage/emulated/0'
local threshold = 10 * 1024 * 1024  -- 10 MB
local maxDepth = 3
local found = {}

local function scan(path, depth)
  if depth > maxDepth then return end
  local items = freefile.list(path) or {}
  for _, f in ipairs(items) do
    local full = path .. '/' .. f.name
    if f.isdir then
      scan(full, depth + 1)
    elseif (f.size or 0) >= threshold then
      found[#found + 1] = { path = full, size = f.size }
    end
  end
end

scan(root, 0)
table.sort(found, function(a, b) return a.size > b.size end)
freefile.log('大文件 (>= ' .. (threshold / 1024 / 1024) .. ' MB):')
for i, f in ipairs(found) do
  if i > 50 then
    freefile.log('... 还有 ' .. (#found - 50) .. ' 项')
    break
  end
  freefile.log(string.format('%.2f MB  %s', f.size / 1024 / 1024, f.path))
end
freefile.log('共找到 ' .. #found .. ' 个大文件')
"""
    ),
    CommonScript(
        nameRes = R.string.script_common_rename_preview,
        code = """-- 批量重命名预览：dry-run 模式，只打印将执行的变更，不实际改名
-- 修改 root 和 pattern/replace 后运行，预览无误再取消 dryRun
local root = '/storage/emulated/0/Download'
local pattern = ' '          -- 文件名中要替换的子串
local replace = '_'          -- 替换为
local dryRun = true          -- true=只预览，false=实际改名

local items = freefile.list(root) or {}
local renamed = 0
for _, f in ipairs(items) do
  if not f.isdir and string.find(f.name, pattern, 1, true) then
    local newName = string.gsub(f.name, pattern, replace)
    if newName ~= f.name then
      local src = root .. '/' .. f.name
      local dst = root .. '/' .. newName
      freefile.log((dryRun and '[预览] ' or '[改名] ') ..
        f.name .. '  ->  ' .. newName)
      if not dryRun then
        local ok = freefile.rename(src, dst)
        if ok then renamed = renamed + 1 end
      else
        renamed = renamed + 1
      end
    end
  end
end
freefile.log((dryRun and '预览 ' or '已改名 ') .. renamed .. ' 个文件')
"""
    ),
    CommonScript(
        nameRes = R.string.script_common_dir_size,
        code = """-- 递归统计目录大小（仅打印一级子目录各自占用的字节）
-- 注意：递归深度过大会很慢，请按需调整 maxDepth
local root = '/storage/emulated/0/Download'
local maxDepth = 4

local function dirSize(path, depth)
  local total = 0
  if depth > maxDepth then return 0 end
  local items = freefile.list(path) or {}
  for _, f in ipairs(items) do
    if f.isdir then
      total = total + dirSize(path .. '/' .. f.name, depth + 1)
    else
      total = total + (f.size or 0)
    end
  end
  return total
end

local items = freefile.list(root) or {}
local results = {}
for _, f in ipairs(items) do
  if f.isdir then
    local s = dirSize(root .. '/' .. f.name, 1)
    results[#results + 1] = { name = f.name, size = s }
  end
end
table.sort(results, function(a, b) return a.size > b.size end)
freefile.log('一级子目录大小 (' .. root .. '):')
for _, r in ipairs(results) do
  freefile.log(string.format('%8.2f MB  %s', r.size / 1024 / 1024, r.name))
end
"""
    ),
    CommonScript(
        nameRes = R.string.script_common_copy_file,
        code = """-- 复制文件示例：把 srcPath 复制为 dstPath
-- 目标父目录不存在时会自动 mkdir
local srcPath = '/storage/emulated/0/Download/example.txt'
local dstPath = '/storage/emulated/0/Documents/copy_of_example.txt'

-- 确保目标父目录存在
local dstDir = dstPath:match('^(.*)/[^/]+$')
if dstDir and not freefile.exists(dstDir) then
  freefile.mkdir(dstDir)
end

local ok = freefile.copy(srcPath, dstPath)
if ok then
  freefile.log('复制成功: ' .. srcPath .. ' -> ' .. dstPath)
else
  freefile.log('复制失败')
end
"""
    ),
    CommonScript(
        nameRes = R.string.script_common_delete_empty,
        code = """-- 递归删除空目录（自底向上，仅删确实为空的目录）
-- 谨慎使用：dryRun=true 只打印不删，确认无误后改为 false
local root = '/storage/emulated/0/Download'
local dryRun = true
local maxDepth = 6
local deleted = 0

local function walk(path, depth)
  if depth > maxDepth then return false end
  local items = freefile.list(path) or {}
  local allEmpty = true
  for _, f in ipairs(items) do
    if f.isdir then
      local sub = path .. '/' .. f.name
      local subEmpty = walk(sub, depth + 1)
      if not subEmpty then allEmpty = false end
    else
      allEmpty = false
    end
  end
  if allEmpty and #items == 0 and path ~= root then
    freefile.log((dryRun and '[预览] ' or '[删除] ') .. path)
    if not dryRun then
      if freefile.delete(path) then deleted = deleted + 1 end
    else
      deleted = deleted + 1
    end
    return true
  end
  return false
end

walk(root, 0)
freefile.log((dryRun and '预览 ' or '已删 ') .. deleted .. ' 个空目录')
"""
    )
)
