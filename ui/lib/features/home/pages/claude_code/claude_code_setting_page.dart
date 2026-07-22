import 'package:flutter/material.dart';
import 'package:ui/services/claude_code_service.dart';

/// Claude Code 设置页面 — 支持多个配置（中转站），可添加、编辑、删除、切换激活。
class ClaudeCodeSettingPage extends StatefulWidget {
  const ClaudeCodeSettingPage({super.key});

  @override
  State<ClaudeCodeSettingPage> createState() => _ClaudeCodeSettingPageState();
}

class _ClaudeCodeSettingPageState extends State<ClaudeCodeSettingPage> {
  List<Map<String, dynamic>> _profiles = [];
  Map<String, dynamic>? _activeProfile;
  bool _loading = true;
  bool _installed = false;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() => _loading = true);
    try {
      final status = await ClaudeCodeService.status();
      _installed = status['installed'] == true;
      _profiles = await ClaudeCodeService.listProfiles();
      final active = await ClaudeCodeService.activeProfile();
      _activeProfile = active;
    } catch (_) {}
    if (mounted) setState(() => _loading = false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Claude Code'),
        actions: [
          IconButton(
            icon: const Icon(Icons.add),
            onPressed: _showAddDialog,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _loadData,
              child: ListView(
                children: [
                  // 安装状态
                  Card(
                    margin: const EdgeInsets.all(16),
                    child: ListTile(
                      leading: Icon(
                        _installed ? Icons.check_circle : Icons.error_outline,
                        color: _installed ? Colors.green : Colors.orange,
                      ),
                      title: Text(_installed ? 'Claude Code 已安装' : 'Claude Code 未安装'),
                      subtitle: Text(_installed
                          ? '可以在 Claude Code 模式中使用'
                          : '请在终端环境中运行 npm install -g @anthropic-ai/claude-code'),
                    ),
                  ),
                  // 配置列表
                  if (_profiles.isEmpty)
                    Padding(
                      padding: const EdgeInsets.all(32),
                      child: Center(
                        child: Text(
                          '还没有配置\n点击右上角 + 添加',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: Colors.grey[600]),
                        ),
                      ),
                    )
                  else
                    ..._profiles.map((p) => _buildProfileTile(p)),
                ],
              ),
            ),
    );
  }

  Widget _buildProfileTile(Map<String, dynamic> profile) {
    final isActive = _activeProfile?['id'] == profile['id'];
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: ListTile(
        leading: Icon(
          isActive ? Icons.radio_button_checked : Icons.radio_button_off,
          color: isActive ? Theme.of(context).colorScheme.primary : Colors.grey,
        ),
        title: Text(profile['name']?.toString() ?? '未命名'),
        subtitle: Text(
          '${profile['baseUrl']?.toString() ?? '默认'}\n${profile['model']?.toString() ?? '默认模型'}',
          maxLines: 2,
        ),
        isThreeLine: true,
        trailing: PopupMenuButton<String>(
          onSelected: (action) {
            if (action == 'activate') {
              _activateProfile(profile['id'].toString());
            } else if (action == 'edit') {
              _showEditDialog(profile);
            } else if (action == 'delete') {
              _deleteProfile(profile['id'].toString());
            }
          },
          itemBuilder: (context) => [
            if (!isActive)
              const PopupMenuItem(value: 'activate', child: Text('设为激活')),
            const PopupMenuItem(value: 'edit', child: Text('编辑')),
            const PopupMenuItem(value: 'delete', child: Text('删除')),
          ],
        ),
        onTap: !isActive ? () => _activateProfile(profile['id'].toString()) : null,
      ),
    );
  }

  void _showAddDialog() {
    _showProfileDialog(isNew: true);
  }

  void _showEditDialog(Map<String, dynamic> profile) {
    _showProfileDialog(isNew: false, profile: profile);
  }

  void _showProfileDialog({required bool isNew, Map<String, dynamic>? profile}) {
    final nameCtrl = TextEditingController(text: profile?['name']?.toString() ?? '');
    final apiKeyCtrl = TextEditingController(text: profile?['apiKey']?.toString() ?? '');
    final baseUrlCtrl = TextEditingController(text: profile?['baseUrl']?.toString() ?? '');
    final modelCtrl = TextEditingController(text: profile?['model']?.toString() ?? '');
    final extraArgsCtrl = TextEditingController(text: profile?['extraArgs']?.toString() ?? '');

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(isNew ? '添加配置' : '编辑配置'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildTextField(nameCtrl, '配置名称', '如：中转站A'),
              const SizedBox(height: 12),
              _buildTextField(apiKeyCtrl, 'API Key', 'ANTHROPIC_API_KEY'),
              const SizedBox(height: 12),
              _buildTextField(baseUrlCtrl, 'Base URL (中转站)', '留空则用官方'),
              const SizedBox(height: 12),
              _buildTextField(modelCtrl, '模型名', '如：claude-sonnet-4-20250514'),
              const SizedBox(height: 12),
              _buildTextField(extraArgsCtrl, '额外参数', '如：--max-turns 10'),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          TextButton(
            onPressed: () async {
              final name = nameCtrl.text.trim();
              final apiKey = apiKeyCtrl.text.trim();
              if (name.isEmpty || apiKey.isEmpty) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('名称和 API Key 不能为空')),
                );
                return;
              }
              Navigator.pop(ctx);
              if (isNew) {
                await ClaudeCodeService.addProfile(
                  name: name,
                  apiKey: apiKey,
                  baseUrl: baseUrlCtrl.text.trim(),
                  model: modelCtrl.text.trim(),
                  extraArgs: extraArgsCtrl.text.trim(),
                );
              } else if (profile != null) {
                await ClaudeCodeService.updateProfile(
                  id: profile['id'].toString(),
                  name: name,
                  apiKey: apiKey,
                  baseUrl: baseUrlCtrl.text.trim(),
                  model: modelCtrl.text.trim(),
                  extraArgs: extraArgsCtrl.text.trim(),
                );
              }
              _loadData();
            },
            child: Text(isNew ? '添加' : '保存'),
          ),
        ],
      ),
    );
  }

  Widget _buildTextField(TextEditingController controller, String label, String hint) {
    return TextField(
      controller: controller,
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        border: const OutlineInputBorder(),
      ),
    );
  }

  Future<void> _activateProfile(String id) async {
    await ClaudeCodeService.activateProfile(id);
    _loadData();
  }

  Future<void> _deleteProfile(String id) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认删除'),
        content: const Text('确定要删除这个配置吗？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('删除')),
        ],
      ),
    );
    if (confirmed == true) {
      await ClaudeCodeService.deleteProfile(id);
      _loadData();
    }
  }
}
