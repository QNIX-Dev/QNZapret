import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../app/theme/app_theme.dart';
import '../../../core/app_metadata.dart';
import '../../../core/motion/app_motion.dart';
import '../../../core/state/app_settings_controller.dart';
import '../../../core/state/package_info_provider.dart';
import '../../../core/state/runtime_controller.dart';
import '../../../core/state/runtime_view_models.dart';
import '../../../core/ui/app_backdrop.dart';
import '../../../core/ui/components/palette_preview_card.dart';
import '../../../core/ui/components/settings_section_card.dart';
import '../../../core/ui/components/staggered_reveal.dart';
import '../../../core/ui/design_tokens.dart';

enum SettingsScreenPresentation { page, dialog }

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({
    this.presentation = SettingsScreenPresentation.dialog,
    super.key,
  });

  static final Uri _githubUri = Uri.parse(
    'https://github.com/QNIX-Dev/QNZapret',
  );
  static const Uri? _telegramUri = null;
  static const Uri? _donateUri = null;

  final SettingsScreenPresentation presentation;

  bool get _isPage => presentation == SettingsScreenPresentation.page;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(appSettingsControllerProvider);
    final settingsController = ref.read(appSettingsControllerProvider.notifier);
    final runtimeView = ref.watch(runtimeControllerProvider);
    final runtimeController = ref.read(runtimeControllerProvider.notifier);
    final packageInfo = ref.watch(packageInfoProvider);
    final effectiveBrightness = Theme.of(context).brightness;
    final showSimulationControls =
        kDebugMode && runtimeView.hasSimulationControls;

    final body = _SettingsBody(
      presentation: presentation,
      settings: settings,
      settingsController: settingsController,
      runtimeView: runtimeView,
      runtimeController: runtimeController,
      packageInfo: packageInfo,
      effectiveBrightness: effectiveBrightness,
      showSimulationControls: showSimulationControls,
      onClose: () => Navigator.of(context).maybePop(),
      onOpenLink: (uri) => _openLink(context, uri),
    );

    if (_isPage) {
      return Scaffold(
        backgroundColor: Colors.transparent,
        body: AppBackdrop(child: body),
      );
    }

    return Material(color: Colors.transparent, child: body);
  }

  Future<void> _openLink(BuildContext context, Uri uri) async {
    final launched = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!launched && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Не удалось открыть ${uri.toString()}')),
      );
    }
  }
}

class _SettingsBody extends StatelessWidget {
  const _SettingsBody({
    required this.presentation,
    required this.settings,
    required this.settingsController,
    required this.runtimeView,
    required this.runtimeController,
    required this.packageInfo,
    required this.effectiveBrightness,
    required this.showSimulationControls,
    required this.onClose,
    required this.onOpenLink,
  });

  final SettingsScreenPresentation presentation;
  final AppSettingsState settings;
  final AppSettingsController settingsController;
  final RuntimeViewState runtimeView;
  final RuntimeController runtimeController;
  final AsyncValue<dynamic> packageInfo;
  final Brightness effectiveBrightness;
  final bool showSimulationControls;
  final VoidCallback onClose;
  final Future<void> Function(Uri uri) onOpenLink;

  bool get _isPage => presentation == SettingsScreenPresentation.page;

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final mobilePage = _isPage && AppBreakpoints.useSettingsPage(size.width);

    return SafeArea(
      minimum: EdgeInsets.all(mobilePage ? 14 : AppSpacing.md),
      child: mobilePage
          ? _SettingsPanel(
              isPage: true,
              fullPage: true,
              settings: settings,
              settingsController: settingsController,
              runtimeView: runtimeView,
              runtimeController: runtimeController,
              packageInfo: packageInfo,
              effectiveBrightness: effectiveBrightness,
              showSimulationControls: showSimulationControls,
              onClose: onClose,
              onOpenLink: onOpenLink,
            )
          : _isPage
          ? Align(
              alignment: Alignment.topCenter,
              child: ConstrainedBox(
                constraints: const BoxConstraints(
                  maxWidth: AppBreakpoints.settingsDialogMaxWidth,
                ),
                child: _SettingsPanel(
                  isPage: true,
                  fullPage: false,
                  settings: settings,
                  settingsController: settingsController,
                  runtimeView: runtimeView,
                  runtimeController: runtimeController,
                  packageInfo: packageInfo,
                  effectiveBrightness: effectiveBrightness,
                  showSimulationControls: showSimulationControls,
                  onClose: onClose,
                  onOpenLink: onOpenLink,
                ),
              ),
            )
          : Center(
              child: ConstrainedBox(
                constraints: BoxConstraints(
                  maxWidth: AppBreakpoints.settingsDialogMaxWidth,
                  maxHeight: size.height - 24,
                ),
                child: _SettingsPanel(
                  isPage: false,
                  fullPage: false,
                  settings: settings,
                  settingsController: settingsController,
                  runtimeView: runtimeView,
                  runtimeController: runtimeController,
                  packageInfo: packageInfo,
                  effectiveBrightness: effectiveBrightness,
                  showSimulationControls: showSimulationControls,
                  onClose: onClose,
                  onOpenLink: onOpenLink,
                ),
              ),
            ),
    );
  }
}

class _SettingsPanel extends StatelessWidget {
  const _SettingsPanel({
    required this.isPage,
    required this.fullPage,
    required this.settings,
    required this.settingsController,
    required this.runtimeView,
    required this.runtimeController,
    required this.packageInfo,
    required this.effectiveBrightness,
    required this.showSimulationControls,
    required this.onClose,
    required this.onOpenLink,
  });

  final bool isPage;
  final bool fullPage;
  final AppSettingsState settings;
  final AppSettingsController settingsController;
  final RuntimeViewState runtimeView;
  final RuntimeController runtimeController;
  final AsyncValue<dynamic> packageInfo;
  final Brightness effectiveBrightness;
  final bool showSimulationControls;
  final VoidCallback onClose;
  final Future<void> Function(Uri uri) onOpenLink;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final extras = context.appThemeExtras;

    return DecoratedBox(
      decoration: BoxDecoration(
        color: fullPage
            ? Colors.transparent
            : isPage
            ? theme.colorScheme.surface.withValues(alpha: 0.94)
            : theme.colorScheme.surface.withValues(alpha: 0.97),
        borderRadius: BorderRadius.circular(fullPage ? 0 : AppRadii.lg),
        border: fullPage ? null : Border.all(color: extras.glassStroke),
        boxShadow: fullPage
            ? const []
            : AppElevations.floating(theme.colorScheme.primary),
      ),
      child: Column(
        children: [
          Padding(
            padding: EdgeInsets.fromLTRB(
              fullPage ? 0 : AppSpacing.lg,
              fullPage ? 0 : AppSpacing.lg,
              fullPage ? 0 : AppSpacing.lg,
              fullPage ? AppSpacing.md : AppSpacing.sm,
            ),
            child: Row(
              children: [
                if (isPage) ...[
                  IconButton(
                    tooltip: 'Назад',
                    onPressed: onClose,
                    icon: const Icon(Icons.arrow_back_rounded),
                  ),
                  const SizedBox(width: AppSpacing.sm),
                ],
                Expanded(
                  child: Text(
                    'Настройки',
                    style: fullPage
                        ? theme.textTheme.headlineLarge
                        : theme.textTheme.headlineMedium,
                  ),
                ),
                if (!isPage)
                  IconButton(
                    tooltip: 'Закрыть',
                    onPressed: onClose,
                    icon: const Icon(Icons.close_rounded),
                  ),
              ],
            ),
          ),
          Expanded(
            child: LayoutBuilder(
              builder: (context, constraints) {
                final wide = constraints.maxWidth >= AppBreakpoints.medium;
                final mobile = constraints.maxWidth < AppBreakpoints.mobile;
                final paletteColumns =
                    constraints.maxWidth >= AppBreakpoints.wide
                    ? 3
                    : constraints.maxWidth >= AppBreakpoints.compact
                    ? 3
                    : constraints.maxWidth >= 430
                    ? 2
                    : 1;
                final paletteAspectRatio =
                    constraints.maxWidth >= AppBreakpoints.medium
                    ? 1.1
                    : paletteColumns == 1
                    ? 1.52
                    : 0.88;

                return SingleChildScrollView(
                  padding: EdgeInsets.fromLTRB(
                    fullPage ? 0 : AppSpacing.lg,
                    0,
                    fullPage ? 0 : AppSpacing.lg,
                    AppSpacing.lg,
                  ),
                  physics: const BouncingScrollPhysics(
                    parent: AlwaysScrollableScrollPhysics(),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      StaggeredReveal(
                        immediate: true,
                        child: _BrandHero(
                          packageInfo: packageInfo,
                          compact: mobile,
                        ),
                      ),
                      SizedBox(height: mobile ? AppSpacing.md : AppSpacing.lg),
                      if (wide)
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Expanded(
                              child: StaggeredReveal(
                                immediate: true,
                                delay: const Duration(milliseconds: 80),
                                child: SettingsSectionCard(
                                  title: 'Режим темы',
                                  description:
                                      'Приложение может следовать системе или работать в выбранном режиме.',
                                  child: _ThemeModeSelector(
                                    selected: settings.themeMode,
                                    onSelected: (mode) async {
                                      HapticFeedback.selectionClick();
                                      await settingsController.setThemeMode(
                                        mode,
                                      );
                                    },
                                  ),
                                ),
                              ),
                            ),
                            if (showSimulationControls) ...[
                              const SizedBox(width: AppSpacing.lg),
                              Expanded(
                                child: StaggeredReveal(
                                  delay: const Duration(milliseconds: 120),
                                  child: SettingsSectionCard(
                                    title: 'Тест запуска',
                                    description:
                                        'Локальные сценарии для проверки интерфейса.',
                                    child: _SimulationScenarioSelector(
                                      runtimeView: runtimeView,
                                      onSelected: (scenario) async {
                                        HapticFeedback.selectionClick();
                                        await runtimeController
                                            .setSimulationScenario(scenario);
                                      },
                                    ),
                                  ),
                                ),
                              ),
                            ],
                          ],
                        )
                      else ...[
                        StaggeredReveal(
                          immediate: true,
                          delay: const Duration(milliseconds: 80),
                          child: SettingsSectionCard(
                            title: 'Режим темы',
                            description:
                                'Приложение может следовать системе или работать в выбранном режиме.',
                            child: _ThemeModeSelector(
                              selected: settings.themeMode,
                              onSelected: (mode) async {
                                HapticFeedback.selectionClick();
                                await settingsController.setThemeMode(mode);
                              },
                            ),
                          ),
                        ),
                        if (showSimulationControls) ...[
                          const SizedBox(height: AppSpacing.lg),
                          StaggeredReveal(
                            delay: const Duration(milliseconds: 120),
                            child: SettingsSectionCard(
                              title: 'Тест запуска',
                              description:
                                  'Локальные сценарии для проверки интерфейса.',
                              child: _SimulationScenarioSelector(
                                runtimeView: runtimeView,
                                onSelected: (scenario) async {
                                  HapticFeedback.selectionClick();
                                  await runtimeController.setSimulationScenario(
                                    scenario,
                                  );
                                },
                              ),
                            ),
                          ),
                        ],
                      ],
                      const SizedBox(height: AppSpacing.lg),
                      StaggeredReveal(
                        immediate: true,
                        delay: const Duration(milliseconds: 180),
                        child: SettingsSectionCard(
                          title: 'Цветовая схема',
                          description: 'Выберите оформление приложения.',
                          child: GridView.builder(
                            shrinkWrap: true,
                            physics: const NeverScrollableScrollPhysics(),
                            itemCount: AppTheme.palettes.length,
                            gridDelegate:
                                SliverGridDelegateWithFixedCrossAxisCount(
                                  crossAxisCount: paletteColumns,
                                  mainAxisSpacing: AppSpacing.md,
                                  crossAxisSpacing: AppSpacing.md,
                                  childAspectRatio: paletteAspectRatio,
                                ),
                            itemBuilder: (context, index) {
                              final palette = AppTheme.palettes[index];
                              return PalettePreviewCard(
                                palette: palette,
                                brightness: effectiveBrightness,
                                selected: settings.paletteId == palette.id,
                                onTap: () async {
                                  HapticFeedback.selectionClick();
                                  await settingsController.setPalette(
                                    palette.id,
                                  );
                                },
                              );
                            },
                          ),
                        ),
                      ),
                      const SizedBox(height: AppSpacing.lg),
                      StaggeredReveal(
                        delay: const Duration(milliseconds: 240),
                        child: SettingsSectionCard(
                          title: 'О приложении',
                          description: 'Версия, автор и ссылки команды.',
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _InfoLine(
                                label: 'Версия',
                                value: packageInfo.when(
                                  data: (info) =>
                                      '$appDisplayVersion (build ${info.buildNumber})',
                                  loading: () =>
                                      '$appDisplayVersion (build $appBuildNumber)',
                                  error: (error, stackTrace) =>
                                      '$appDisplayVersion (build $appBuildNumber)',
                                ),
                              ),
                              const SizedBox(height: AppSpacing.sm),
                              const _InfoLine(
                                label: 'Автор',
                                value: 'QNIX-Dev',
                              ),
                              const SizedBox(height: AppSpacing.md),
                              LayoutBuilder(
                                builder: (context, constraints) {
                                  final twoColumns =
                                      constraints.maxWidth >= 520;
                                  final itemWidth = twoColumns
                                      ? (constraints.maxWidth - AppSpacing.md) /
                                            2
                                      : constraints.maxWidth;

                                  return Wrap(
                                    spacing: AppSpacing.md,
                                    runSpacing: AppSpacing.md,
                                    children: [
                                      SizedBox(
                                        width: itemWidth,
                                        child: _AboutAction(
                                          label: 'GitHub',
                                          icon: Icons.code_rounded,
                                          enabled: true,
                                          onTap: () => onOpenLink(
                                            SettingsScreen._githubUri,
                                          ),
                                        ),
                                      ),
                                      SizedBox(
                                        width: itemWidth,
                                        child: _AboutAction(
                                          label: 'Telegram',
                                          icon: Icons.forum_rounded,
                                          enabled:
                                              SettingsScreen._telegramUri !=
                                              null,
                                          onTap:
                                              SettingsScreen._telegramUri ==
                                                  null
                                              ? null
                                              : () => onOpenLink(
                                                  SettingsScreen._telegramUri!,
                                                ),
                                        ),
                                      ),
                                    ],
                                  );
                                },
                              ),
                              const SizedBox(height: AppSpacing.md),
                              SizedBox(
                                width: double.infinity,
                                child: FilledButton.icon(
                                  onPressed: SettingsScreen._donateUri == null
                                      ? null
                                      : () => onOpenLink(
                                          SettingsScreen._donateUri!,
                                        ),
                                  icon: const Icon(Icons.coffee_rounded),
                                  label: const Text('Угостить команду кофе'),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _BrandHero extends StatelessWidget {
  const _BrandHero({required this.packageInfo, required this.compact});

  final AsyncValue<dynamic> packageInfo;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final extras = context.appThemeExtras;

    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [
            Theme.of(context).colorScheme.primary.withValues(alpha: 0.14),
            extras.glassSurface,
          ],
        ),
        borderRadius: BorderRadius.circular(
          compact ? AppRadii.md : AppRadii.lg,
        ),
        border: Border.all(color: extras.glassStroke),
      ),
      child: Padding(
        padding: EdgeInsets.all(compact ? AppSpacing.md : AppSpacing.lg),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(compact ? 20 : 26),
              child: Image.asset(
                'assets/branding/logo/qnzapret_logo.png',
                width: compact ? 64 : 88,
                height: compact ? 64 : 88,
                fit: BoxFit.cover,
              ),
            ),
            SizedBox(width: compact ? AppSpacing.md : AppSpacing.lg),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(appDisplayName, style: theme.textTheme.headlineLarge),
                  const SizedBox(height: 6),
                  Text(
                    packageInfo.when(
                      data: (info) =>
                          'Версия $appDisplayVersion • build ${info.buildNumber}',
                      loading: () =>
                          'Версия $appDisplayVersion • build $appBuildNumber',
                      error: (error, stackTrace) =>
                          'Версия $appDisplayVersion • build $appBuildNumber',
                    ),
                    style: theme.textTheme.bodyLarge?.copyWith(
                      color: extras.mutedForeground,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ThemeModeSelector extends StatelessWidget {
  const _ThemeModeSelector({required this.selected, required this.onSelected});

  final ThemeMode selected;
  final ValueChanged<ThemeMode> onSelected;

  @override
  Widget build(BuildContext context) {
    final extras = context.appThemeExtras;

    return DecoratedBox(
      decoration: BoxDecoration(
        color: Theme.of(
          context,
        ).colorScheme.surfaceContainerHigh.withValues(alpha: 0.58),
        borderRadius: BorderRadius.circular(AppRadii.pill),
        border: Border.all(color: extras.glassStroke),
      ),
      child: Padding(
        padding: const EdgeInsets.all(4),
        child: Row(
          children: [
            _ThemeSegment(
              label: 'Система',
              tooltip: 'Следовать системе',
              icon: Icons.brightness_auto_rounded,
              selected: selected == ThemeMode.system,
              onTap: () => onSelected(ThemeMode.system),
            ),
            _ThemeSegment(
              label: 'Светлая',
              tooltip: 'Светлая тема',
              icon: Icons.wb_sunny_rounded,
              selected: selected == ThemeMode.light,
              onTap: () => onSelected(ThemeMode.light),
            ),
            _ThemeSegment(
              label: 'Тёмная',
              tooltip: 'Тёмная тема',
              icon: Icons.dark_mode_rounded,
              selected: selected == ThemeMode.dark,
              onTap: () => onSelected(ThemeMode.dark),
            ),
          ],
        ),
      ),
    );
  }
}

class _ThemeSegment extends StatefulWidget {
  const _ThemeSegment({
    required this.label,
    required this.tooltip,
    required this.icon,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final String tooltip;
  final IconData icon;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<_ThemeSegment> createState() => _ThemeSegmentState();
}

class _ThemeSegmentState extends State<_ThemeSegment> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final selected = widget.selected;
    final foreground = selected
        ? theme.colorScheme.onPrimaryContainer
        : theme.colorScheme.onSurface.withValues(alpha: 0.72);

    return Expanded(
      child: Tooltip(
        message: widget.tooltip,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapDown: (_) => setState(() => _pressed = true),
          onTapCancel: () => setState(() => _pressed = false),
          onTapUp: (_) => setState(() => _pressed = false),
          onTap: widget.onTap,
          child: AnimatedScale(
            duration: AppMotionDurations.fast,
            curve: AppMotionCurves.decelerate,
            scale: _pressed ? 0.97 : 1,
            child: AnimatedContainer(
              duration: AppMotionDurations.standard,
              curve: AppMotionCurves.standard,
              height: 42,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: selected
                    ? theme.colorScheme.primaryContainer
                    : Colors.transparent,
                borderRadius: BorderRadius.circular(AppRadii.pill),
                boxShadow: selected
                    ? [
                        BoxShadow(
                          color: theme.colorScheme.primary.withValues(
                            alpha: 0.16,
                          ),
                          blurRadius: 14,
                          offset: const Offset(0, 6),
                        ),
                      ]
                    : const [],
              ),
              child: FittedBox(
                fit: BoxFit.scaleDown,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(widget.icon, size: 17, color: foreground),
                    const SizedBox(width: 6),
                    Text(
                      widget.label,
                      maxLines: 1,
                      style: theme.textTheme.labelLarge?.copyWith(
                        color: foreground,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SimulationScenarioSelector extends StatelessWidget {
  const _SimulationScenarioSelector({
    required this.runtimeView,
    required this.onSelected,
  });

  final RuntimeViewState runtimeView;
  final ValueChanged<RuntimeLaunchScenario> onSelected;

  @override
  Widget build(BuildContext context) {
    final selectedScenario = runtimeView.selectedScenario;

    return Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      children: runtimeView.availableScenarios.map((scenario) {
        return _ModeChip(
          label: scenario.title,
          icon: Icons.science_rounded,
          selected: selectedScenario == scenario,
          onTap: () => onSelected(scenario),
        );
      }).toList(),
    );
  }
}

class _ModeChip extends StatelessWidget {
  const _ModeChip({
    required this.label,
    required this.icon,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final IconData icon;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return ChoiceChip(
      selected: selected,
      onSelected: (_) => onTap(),
      avatar: Icon(
        icon,
        size: 18,
        color: selected
            ? theme.colorScheme.onPrimaryContainer
            : theme.colorScheme.onSurface,
      ),
      label: Text(label),
      selectedColor: theme.colorScheme.primaryContainer,
      backgroundColor: theme.colorScheme.surfaceContainerHigh,
      side: BorderSide(color: context.appThemeExtras.glassStroke),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadii.pill),
      ),
      labelStyle: theme.textTheme.labelLarge?.copyWith(
        color: selected
            ? theme.colorScheme.onPrimaryContainer
            : theme.colorScheme.onSurface,
      ),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
    );
  }
}

class _InfoLine extends StatelessWidget {
  const _InfoLine({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 82,
          child: Text(
            label,
            style: Theme.of(context).textTheme.labelLarge?.copyWith(
              color: context.appThemeExtras.mutedForeground,
            ),
          ),
        ),
        Expanded(
          child: Text(value, style: Theme.of(context).textTheme.bodyLarge),
        ),
      ],
    );
  }
}

class _AboutAction extends StatelessWidget {
  const _AboutAction({
    required this.label,
    required this.icon,
    required this.enabled,
    required this.onTap,
  });

  final String label;
  final IconData icon;
  final bool enabled;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: enabled ? onTap : null,
      icon: Icon(icon),
      label: Text(label),
    );
  }
}
