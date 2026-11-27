internal sealed class VolumeTray : ApplicationContext
{
    private readonly CancellationTokenSource stop = new();
    private readonly ContextMenuStrip menu = new();
    private readonly ToolStripMenuItem status = new("正在连接设备…") { Enabled = false };
    private readonly NotifyIcon tray;
    private readonly Icon icon;
    private Task? worker;
    private bool exiting;
    private readonly Guid bound;
    private readonly System.Windows.Forms.Timer routeTimer = new() { Interval = 100 };
    internal int Result { get; private set; }

    internal VolumeTray(Guid bound, EventWaitHandle sessionStop)
    {
        this.bound = bound;
        routeTimer.Tick += async (_, _) =>
        {
            if (sessionStop.WaitOne(0)) await Quit();
        };
        using var stream = typeof(VolumeTray).Assembly.GetManifestResourceStream("Volume.ico")!;
        using var source = new Icon(stream, SystemInformation.SmallIconSize);
        icon = (Icon)source.Clone();
        menu.Items.Add(status);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("退出", null, async (_, _) => await Quit());
        tray = new NotifyIcon
        {
            Icon = icon,
            Text = "NunchakuVolumeSync · 正在连接",
            ContextMenuStrip = menu,
            Visible = true
        };
        Application.Idle += Start;
        routeTimer.Start();
    }

    private void Start(object? sender, EventArgs e)
    {
        Application.Idle -= Start;
        if (exiting) return;
        IProgress<int> volume = new Progress<int>(value =>
        {
            if (exiting) return;
            status.Text = $"正在同步 · VOL {value}";
            tray.Text = $"NunchakuVolumeSync · VOL {value}";
        });
        worker = Observe();

        async Task Observe()
        {
            try { await Task.Run(() => Program.Run([], stop.Token, volume.Report, bound)); }
            catch (Exception ex)
            {
                if (exiting) return;
                status.Text = "同步已停止";
                status.ToolTipText = ex.Message;
                tray.Text = "NunchakuVolumeSync · 同步已停止";
                Result = 1;
                ExitThread();
            }
        }
    }

    private async Task Quit()
    {
        if (exiting) return;
        exiting = true;
        status.Text = "正在退出…";
        stop.Cancel();
        if (worker != null) await worker;
        tray.Visible = false;
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            Application.Idle -= Start;
            routeTimer.Dispose();
            tray.Dispose();
            menu.Dispose();
            icon.Dispose();
            stop.Dispose();
        }
        base.Dispose(disposing);
    }
}
