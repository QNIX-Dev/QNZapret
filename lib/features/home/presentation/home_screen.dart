import 'package:flutter/material.dart';

import '../../../app/theme/app_theme.dart';
import '../../../core/backend/backend.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.runtime});

  final ProxyRuntime runtime;

  static const _platforms = [
    _PlatformCardData(
      label: 'Android',
      caption: 'foreground service, local proxy, VPN runtime',
      icon: Icons.android_rounded,
    ),
    _PlatformCardData(
      label: 'Linux',
      caption: 'desktop control shell, logs, runtime status',
      icon: Icons.computer_rounded,
    ),
    _PlatformCardData(
      label: 'Windows',
      caption: 'native runner, diagnostics, update surface',
      icon: Icons.window_rounded,
    ),
  ];

  static const _productPoints = [
    _FeaturePoint(
      title: 'One runtime surface',
      description:
          'Single Flutter shell with platform-specific backend bridges hidden behind a shared contract.',
    ),
    _FeaturePoint(
      title: 'Backend-ready hooks',
      description:
          'Dedicated integration layer for strategy profiles, VPN lifecycle, and native runtime diagnostics.',
    ),
    _FeaturePoint(
      title: 'Commercial-first UX',
      description:
          'The shell already looks like a real product and can evolve into onboarding, dashboard, and diagnostics flows.',
    ),
  ];

  static const _nextMilestones = [
    'run Android device smoke with establishTunnel enabled',
    'harden TCP out-of-order buffering and write backpressure',
    'add runtime logs, connection presets, and desktop bridge contracts',
  ];

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late ProxyRuntimeController _runtimeController;

  @override
  void initState() {
    super.initState();
    _runtimeController = ProxyRuntimeController(runtime: widget.runtime)
      ..initialize();
  }

  @override
  void didUpdateWidget(covariant HomeScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.runtime != widget.runtime) {
      _runtimeController.dispose();
      _runtimeController = ProxyRuntimeController(runtime: widget.runtime)
        ..initialize();
    }
  }

  @override
  void dispose() {
    _runtimeController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return AnimatedBuilder(
      animation: _runtimeController,
      builder: (context, _) {
        final runtimeState = _runtimeController.snapshot;
        return Scaffold(
          body: DecoratedBox(
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color(0xFF090A0C),
                  Color(0xFF12161B),
                  Color(0xFF1B232A),
                ],
              ),
            ),
            child: Stack(
              children: [
                const _BackdropOrb(
                  alignment: Alignment.topRight,
                  size: 320,
                  color: Color(0x33F3B23A),
                ),
                const _BackdropOrb(
                  alignment: Alignment.centerLeft,
                  size: 360,
                  color: Color(0x228BF0C7),
                ),
                SafeArea(
                  child: Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 1200),
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.all(24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Wrap(
                              spacing: 12,
                              runSpacing: 12,
                              children: [
                                _StatusPill(
                                  label: 'Flutter shell ready',
                                  tone: AppTheme.mint,
                                ),
                                _StatusPill(
                                  label: 'Android • Linux • Windows',
                                  tone: AppTheme.amber,
                                ),
                                _StatusPill(
                                  label:
                                      _runtimeController.lastFailure?.message ??
                                      runtimeState.message,
                                  tone: Colors.white,
                                ),
                              ],
                            ),
                            const SizedBox(height: 28),
                            LayoutBuilder(
                              builder: (context, constraints) {
                                final wide = constraints.maxWidth >= 880;
                                if (wide) {
                                  return Row(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Expanded(
                                        flex: 7,
                                        child: _HeroPanel(theme: theme),
                                      ),
                                      const SizedBox(width: 20),
                                      Expanded(
                                        flex: 4,
                                        child: _RuntimePanel(
                                          runtimeState: runtimeState,
                                        ),
                                      ),
                                    ],
                                  );
                                }

                                return Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    _HeroPanel(theme: theme),
                                    const SizedBox(height: 20),
                                    _RuntimePanel(runtimeState: runtimeState),
                                  ],
                                );
                              },
                            ),
                            const SizedBox(height: 20),
                            const _SectionTitle(
                              eyebrow: 'Platforms',
                              title: 'One product surface for three targets',
                            ),
                            const SizedBox(height: 12),
                            Wrap(
                              spacing: 16,
                              runSpacing: 16,
                              children: HomeScreen._platforms
                                  .map((item) => _PlatformCard(data: item))
                                  .toList(),
                            ),
                            const SizedBox(height: 28),
                            const _SectionTitle(
                              eyebrow: 'Foundation',
                              title: 'Initial architecture for backend wiring',
                            ),
                            const SizedBox(height: 12),
                            Wrap(
                              spacing: 16,
                              runSpacing: 16,
                              children: HomeScreen._productPoints
                                  .map((item) => _FeatureCard(point: item))
                                  .toList(),
                            ),
                            const SizedBox(height: 28),
                            const _SectionTitle(
                              eyebrow: 'Next',
                              title: 'Closest implementation slice',
                            ),
                            const SizedBox(height: 12),
                            const _MilestonePanel(
                              items: HomeScreen._nextMilestones,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _HeroPanel extends StatelessWidget {
  const _HeroPanel({required this.theme});

  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(28),
      decoration: BoxDecoration(
        color: Colors.white.withAlpha(15),
        borderRadius: BorderRadius.circular(28),
        border: Border.all(color: Colors.white.withAlpha(20)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'QNZapret',
            style: theme.textTheme.displayMedium?.copyWith(
              fontWeight: FontWeight.w800,
              height: 0.94,
              letterSpacing: -1.8,
            ),
          ),
          const SizedBox(height: 16),
          Text(
            'Кроссплатформенный frontend shell для будущего клиента с native strategy runtime и сильной продуктовой подачей.',
            style: theme.textTheme.titleLarge?.copyWith(
              color: Colors.white.withAlpha(219),
              height: 1.35,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 18),
          Text(
            'На этом этапе мы подняли общий Flutter-каркас, подключили Android runtime bridge к общей Dart-поверхности и подготовили место для дальнейшей реализации userspace forwarding.',
            style: theme.textTheme.bodyLarge?.copyWith(
              color: Colors.white.withAlpha(179),
              height: 1.5,
            ),
          ),
          const SizedBox(height: 24),
          const Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              _MetricTile(value: '3', label: 'target platforms'),
              _MetricTile(value: '1', label: 'shared Flutter shell'),
              _MetricTile(value: '1', label: 'Android strategy engine'),
            ],
          ),
        ],
      ),
    );
  }
}

class _RuntimePanel extends StatelessWidget {
  const _RuntimePanel({required this.runtimeState});

  final ProxyRuntimeSnapshot runtimeState;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final stateLabel = switch (runtimeState.state) {
      ProxyRuntimeState.idle => 'Idle',
      ProxyRuntimeState.starting => 'Starting',
      ProxyRuntimeState.running => 'Running',
      ProxyRuntimeState.stopping => 'Stopping',
      ProxyRuntimeState.failed => 'Failed',
    };

    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: const Color(0xFFF6F0E6),
        borderRadius: BorderRadius.circular(28),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Runtime entry point',
            style: theme.textTheme.titleLarge?.copyWith(
              color: AppTheme.ink,
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(height: 14),
          _InfoRow(label: 'Platform', value: runtimeState.platform.name),
          _InfoRow(label: 'State', value: stateLabel),
          _InfoRow(
            label: 'Bridge',
            value: runtimeState.backendConnected ? 'connected' : 'reserved',
          ),
          const SizedBox(height: 18),
          Text(
            'Следующий логический слой: Android device smoke для TUN forwarding, diagnostics и устойчивого runtime feedback.',
            style: theme.textTheme.bodyMedium?.copyWith(
              color: AppTheme.inkSoft,
              height: 1.45,
            ),
          ),
        ],
      ),
    );
  }
}

class _MilestonePanel extends StatelessWidget {
  const _MilestonePanel({required this.items});

  final List<String> items;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: Colors.white.withAlpha(15),
        borderRadius: BorderRadius.circular(28),
        border: Border.all(color: Colors.white.withAlpha(20)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Next build slice',
            style: theme.textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(height: 16),
          for (var index = 0; index < items.length; index++) ...[
            _StepRow(label: items[index]),
            if (index != items.length - 1) const SizedBox(height: 12),
          ],
        ],
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.eyebrow, required this.title});

  final String eyebrow;
  final String title;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          eyebrow.toUpperCase(),
          style: theme.textTheme.labelLarge?.copyWith(
            color: AppTheme.amber,
            fontWeight: FontWeight.w800,
            letterSpacing: 1.4,
          ),
        ),
        const SizedBox(height: 6),
        Text(
          title,
          style: theme.textTheme.headlineSmall?.copyWith(
            fontWeight: FontWeight.w800,
          ),
        ),
      ],
    );
  }
}

class _PlatformCard extends StatelessWidget {
  const _PlatformCard({required this.data});

  final _PlatformCardData data;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return ConstrainedBox(
      constraints: const BoxConstraints(minWidth: 220, maxWidth: 360),
      child: Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: Colors.white.withAlpha(15),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: Colors.white.withAlpha(20)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(data.icon, size: 28, color: AppTheme.mint),
            const SizedBox(height: 16),
            Text(
              data.label,
              style: theme.textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              data.caption,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: Colors.white.withAlpha(179),
                height: 1.45,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FeatureCard extends StatelessWidget {
  const _FeatureCard({required this.point});

  final _FeaturePoint point;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return ConstrainedBox(
      constraints: const BoxConstraints(minWidth: 220, maxWidth: 360),
      child: Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: const Color(0xFFF6F0E6),
          borderRadius: BorderRadius.circular(24),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              point.title,
              style: theme.textTheme.titleMedium?.copyWith(
                color: AppTheme.ink,
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              point.description,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: AppTheme.inkSoft,
                height: 1.45,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.label, required this.tone});

  final String label;
  final Color tone;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: tone.withAlpha(36),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: tone.withAlpha(72)),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(
          color: Colors.white,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _MetricTile extends StatelessWidget {
  const _MetricTile({required this.value, required this.label});

  final String value;
  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Container(
      width: 170,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white.withAlpha(15),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: Colors.white.withAlpha(20)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            value,
            style: theme.textTheme.headlineMedium?.copyWith(
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            label,
            style: theme.textTheme.bodySmall?.copyWith(
              color: Colors.white.withAlpha(168),
            ),
          ),
        ],
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: AppTheme.slate,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          Text(
            value,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: AppTheme.ink,
              fontWeight: FontWeight.w800,
            ),
          ),
        ],
      ),
    );
  }
}

class _StepRow extends StatelessWidget {
  const _StepRow({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 10,
          height: 10,
          margin: const EdgeInsets.only(top: 6),
          decoration: const BoxDecoration(
            color: Color(0xFF8BF0C7),
            shape: BoxShape.circle,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            label,
            style: theme.textTheme.bodyLarge?.copyWith(
              color: Colors.white.withAlpha(209),
              height: 1.45,
            ),
          ),
        ),
      ],
    );
  }
}

class _BackdropOrb extends StatelessWidget {
  const _BackdropOrb({
    required this.alignment,
    required this.size,
    required this.color,
  });

  final Alignment alignment;
  final double size;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Align(
        alignment: alignment,
        child: Container(
          width: size,
          height: size,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            gradient: RadialGradient(colors: [color, Colors.transparent]),
          ),
        ),
      ),
    );
  }
}

class _PlatformCardData {
  const _PlatformCardData({
    required this.label,
    required this.caption,
    required this.icon,
  });

  final String label;
  final String caption;
  final IconData icon;
}

class _FeaturePoint {
  const _FeaturePoint({required this.title, required this.description});

  final String title;
  final String description;
}
