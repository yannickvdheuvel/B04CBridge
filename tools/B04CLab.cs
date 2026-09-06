using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Foundation;
using Windows.Storage.Streams;

// Lab-bridge naar het HUIYE B04C-BF display, rechtstreeks vanaf Windows.
// Doel: het protocol uitproberen zonder de APK-cyclus via GitHub Actions.
//
// Bewust GEEN System.Runtime.WindowsRuntime: die facade is gebouwd tegen de union-Windows.winmd
// uit de Windows SDK, die hier niet staat. Daarom async via IAsyncOperation.Completed en
// buffers via DataReader/DataWriter -- allemaal pure WinRT uit C:\Windows\System32\WinMetadata.
class B04CLab
{
    static readonly Guid SVC   = new Guid("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static readonly Guid WRITE = new Guid("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    static readonly Guid NOTIF = new Guid("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    static readonly byte[] AES_KEY = Encoding.ASCII.GetBytes("2CTDU40qNyCgTjb1");

    static StreamWriter _log;
    static GattCharacteristic _wc;
    static readonly List<byte> _acc = new List<byte>();
    static readonly object _accLock = new object();
    static readonly Dictionary<string, byte[]> _last = new Dictionary<string, byte[]>();
    static readonly Dictionary<string, DateTime> _lastLogged = new Dictionary<string, DateTime>();
    static bool _awaitingAuth, _ready;
    static int _seq;

    // ---------- WinRT helpers ----------
    static T Wait<T>(IAsyncOperation<T> op)
    {
        var done = new ManualResetEventSlim(false);
        T result = default(T);
        Exception err = null;
        op.Completed = new AsyncOperationCompletedHandler<T>(delegate(IAsyncOperation<T> o, AsyncStatus s)
        {
            try
            {
                if (s == AsyncStatus.Completed) result = o.GetResults();
                else err = new Exception("async status " + s);
            }
            catch (Exception ex) { err = ex; }
            done.Set();
        });
        if (!done.Wait(20000)) throw new TimeoutException("WinRT timeout");
        if (err != null) throw err;
        return result;
    }
    static IBuffer ToBuffer(byte[] b)
    {
        var dw = new DataWriter();
        dw.WriteBytes(b);
        return dw.DetachBuffer();
    }
    static byte[] FromBuffer(IBuffer buf)
    {
        var dr = DataReader.FromBuffer(buf);
        var a = new byte[buf.Length];
        dr.ReadBytes(a);
        return a;
    }

    static void Say(string s)
    {
        string line = DateTime.Now.ToString("HH:mm:ss.fff") + " " + s;
        Console.WriteLine(line);
        if (_log != null) _log.WriteLine(line);
    }
    static string Hex(byte[] b)
    {
        var sb = new StringBuilder();
        foreach (byte x in b) { if (sb.Length > 0) sb.Append(' '); sb.Append(x.ToString("X2")); }
        return sb.ToString();
    }

    // ---------- protocol ----------
    static byte[] Frame(int target, int sub, int param, byte[] payload)
    {
        if (payload == null) payload = new byte[0];
        var pre = new byte[7 + payload.Length];
        pre[0] = 0x55; pre[1] = 0xAA; pre[2] = (byte)payload.Length;
        pre[3] = 0x11; pre[4] = (byte)target; pre[5] = (byte)sub; pre[6] = (byte)param;
        Array.Copy(payload, 0, pre, 7, payload.Length);
        int sum = 0;
        foreach (byte b in pre) sum += b;
        int cs1 = (0xFE - (sum & 0xFF)) & 0xFF;
        int cs2 = (0x100 - ((sum >> 8) & 0xFF)) & 0xFF;
        var outb = new byte[pre.Length + 2];
        Array.Copy(pre, outb, pre.Length);
        outb[pre.Length] = (byte)cs1;
        outb[pre.Length + 1] = (byte)cs2;
        return outb;
    }
    static byte[] U24(int v)
    {
        if (v < 0) v = 0;
        if (v > 0xFFFFFF) v = 0xFFFFFF;
        return new byte[] { (byte)(v & 255), (byte)((v >> 8) & 255), (byte)((v >> 16) & 255) };
    }
    static byte[] U32(long v)
    {
        if (v < 0) v = 0;
        return new byte[] { (byte)(v & 255), (byte)((v >> 8) & 255), (byte)((v >> 16) & 255), (byte)((v >> 24) & 255) };
    }
    static byte[] NavFrame(int curD, int curM, int nxtD, int nxtM, int nnD, int nnM, int total)
    {
        var p = new List<byte>();
        p.Add((byte)(_seq++ & 255));
        p.Add(0x02);
        p.AddRange(U24(curD)); p.Add((byte)curM);
        p.AddRange(U24(nxtD)); p.Add((byte)nxtM);
        p.AddRange(U24(nnD));  p.Add((byte)nnM);
        p.AddRange(U32(total));
        return Frame(0xF1, 0x03, 0x00, p.ToArray());
    }

    static void Send(byte[] bytes, string why)
    {
        var r = Wait(_wc.WriteValueAsync(ToBuffer(bytes), GattWriteOption.WriteWithResponse));
        Say("TX " + Hex(bytes) + "  [" + why + "] -> " + r);
    }

    static int U16(byte[] v, int i) { return v[i] | (v[i + 1] << 8); }
    static long U32R(byte[] v, int i) { return (long)v[i] | ((long)v[i + 1] << 8) | ((long)v[i + 2] << 16) | ((long)v[i + 3] << 24); }

    static void DecodeTelemetry(byte[] f, int tgt, int sub, int par)
    {
        if (tgt == 0x11 && sub == 0x06 && par == 0x01 && f.Length >= 28)
        {
            double speed = U16(f, 16) / 100.0;
            long trip = U32R(f, 18) * 10;
            long odo = U32R(f, 22) * 10;
            Say(string.Format(CultureInfo.InvariantCulture, "  -> RIT: {0:0.0} km/h  trip {1:0.00} km  odo {2:0.00} km",
                speed, trip / 1000.0, odo / 1000.0));
        }
        if (tgt == 0x11 && sub == 0x06 && par == 0x09 && f.Length >= 25)
        {
            int secs = U16(f, 7);
            double avg = U16(f, 11) / 100.0;
            Say(string.Format(CultureInfo.InvariantCulture, "  -> STAT: gem {0:0.00} km/h  rijtijd {1:00}:{2:00}:{3:00}",
                avg, secs / 3600, (secs / 60) % 60, secs % 60));
        }
    }

    static void DecodeFrame(byte[] f)
    {
        int dir = f[3], tgt = f[4], sub = f[5], par = f[6];
        string key = string.Format("{0:X2}/{1:X2}/{2:X2}", tgt, sub, par);

        byte[] prev;
        bool isNew = !_last.TryGetValue(key, out prev);
        string diff = null;
        if (!isNew && prev.Length == f.Length)
        {
            var parts = new List<string>();
            for (int i = 7; i < f.Length - 2; i++)
                if (prev[i] != f[i]) parts.Add(string.Format("[{0}] {1:X2}->{2:X2}", i, prev[i], f[i]));
            if (parts.Count > 0) diff = string.Join(" ", parts.ToArray());
        }
        _last[key] = f;

        DateTime now = DateTime.Now;
        DateTime lastAt;
        bool throttled = _lastLogged.TryGetValue(key, out lastAt) && (now - lastAt).TotalMilliseconds < 900;

        if (isNew)
        {
            _lastLogged[key] = now;
            Say("RX NIEUW " + key + "  " + Hex(f));
            DecodeTelemetry(f, tgt, sub, par);
        }
        else if (diff != null && !throttled)
        {
            _lastLogged[key] = now;
            Say("RX " + key + "  wijzigt " + diff);
            DecodeTelemetry(f, tgt, sub, par);
        }

        if (dir == 0x10 && tgt == 0x11 && sub == 0x04 && par == 0 && !_awaitingAuth && f.Length >= 11)
        {
            _awaitingAuth = true;
            var ch = new byte[4];
            Array.Copy(f, 7, ch, 0, 4);
            Say("Challenge " + Hex(ch) + " -> authenticeren");
            var plain = new byte[16];
            Array.Copy(ch, plain, 4);
            using (var aes = Aes.Create())
            {
                aes.Mode = CipherMode.ECB;
                aes.Padding = PaddingMode.None;
                aes.Key = AES_KEY;
                byte[] cipher = aes.CreateEncryptor().TransformFinalBlock(plain, 0, 16);
                Send(Frame(0x10, 0x20, 0x00, cipher), "auth");
            }
            return;
        }
        if (dir == 0x10 && tgt == 0x11 && par == 0 && _awaitingAuth)
        {
            if (f[7] == 0)
            {
                _awaitingAuth = false;
                _ready = true;
                Say("AUTH OK - display klaar");
                Send(Frame(0x10, 0x02, 0x3E, U32(DateTimeOffset.UtcNow.ToUnixTimeSeconds())), "timesync");
            }
            else Say(string.Format("Auth-antwoord sub={0:X2} payload={1:X2}", sub, f[7]));
        }
    }

    static void OnValue(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        byte[] chunk = FromBuffer(args.CharacteristicValue);
        var frames = new List<byte[]>();
        lock (_accLock)
        {
            _acc.AddRange(chunk);
            while (_acc.Count >= 9)
            {
                int i = 0;
                while (i < _acc.Count - 1 && !(_acc[i] == 0x55 && _acc[i + 1] == 0xAA)) i++;
                if (i > 0) _acc.RemoveRange(0, i);
                if (_acc.Count < 9) break;
                int total = 9 + _acc[2];
                if (_acc.Count < total) break;
                frames.Add(_acc.GetRange(0, total).ToArray());
                _acc.RemoveRange(0, total);
            }
        }
        foreach (var f in frames)
        {
            try { DecodeFrame(f); }
            catch (Exception ex) { Say("decode-fout: " + ex.Message); }
        }
    }

    static void Main(string[] args)
    {
        try { Run(args); }
        catch (Exception ex) { Say("FATAAL: " + ex); }
        finally { if (_log != null) _log.Flush(); }
    }

    static void Run(string[] args)
    {
        string addr    = args.Length > 0 ? args[0] : "70DEF9D3A09E";
        string cmdFile = args.Length > 1 ? args[1] : "cmd.txt";
        string logFile = args.Length > 2 ? args[2] : "lab.log";
        int minutes    = args.Length > 3 ? int.Parse(args[3]) : 30;

        _log = new StreamWriter(logFile, true);
        _log.AutoFlush = true;
        Say("Verbinden met " + addr + " ...");

        ulong a = ulong.Parse(addr, NumberStyles.HexNumber);
        var dev = Wait(BluetoothLEDevice.FromBluetoothAddressAsync(a));
        if (dev == null) { Say("GEEN DEVICE"); return; }
        dev.ConnectionStatusChanged += delegate(BluetoothLEDevice s, object e) { Say("Verbindingsstatus: " + s.ConnectionStatus); };

        var sr = Wait(dev.GetGattServicesForUuidAsync(SVC, BluetoothCacheMode.Uncached));
        if (sr.Services.Count == 0) { Say("NUS niet gevonden: " + sr.Status); return; }
        var svc = sr.Services[0];
        var cr = Wait(svc.GetCharacteristicsAsync(BluetoothCacheMode.Uncached));
        _wc = cr.Characteristics.FirstOrDefault(c => c.Uuid == WRITE);
        var nc = cr.Characteristics.FirstOrDefault(c => c.Uuid == NOTIF);
        if (_wc == null || nc == null) { Say("NUS characteristics ontbreken"); return; }
        Say("Verbonden. status=" + dev.ConnectionStatus + " MaxPdu=" + svc.Session.MaxPduSize);

        nc.ValueChanged += OnValue;
        var st = Wait(nc.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Notify));
        Say("Notify: " + st);

        Send(Frame(0x10, 0x01, 0x00, new byte[] { 0x04 }), "challenge-request");

        if (!File.Exists(cmdFile)) File.WriteAllText(cmdFile, "");
        DateTime deadline = DateTime.Now.AddMinutes(minutes);
        while (DateTime.Now < deadline)
        {
            string[] lines = new string[0];
            try
            {
                var fi = new FileInfo(cmdFile);
                if (fi.Exists && fi.Length > 0)
                {
                    lines = File.ReadAllLines(cmdFile);
                    File.WriteAllText(cmdFile, "");
                }
            }
            catch { }

            foreach (string rawLine in lines)
            {
                string line = rawLine.Trim();
                if (line.Length == 0) continue;
                var p = line.Split(new char[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                Say("CMD " + line);
                try
                {
                    switch (p[0].ToLowerInvariant())
                    {
                        case "quit":
                            deadline = DateTime.Now;
                            break;
                        case "raw":
                            Send(p.Skip(1).Select(x => Convert.ToByte(x, 16)).ToArray(), "raw");
                            break;
                        case "frame":
                            Send(Frame(Convert.ToInt32(p[1], 16), Convert.ToInt32(p[2], 16), Convert.ToInt32(p[3], 16),
                                p.Skip(4).Select(x => Convert.ToByte(x, 16)).ToArray()), "frame");
                            break;
                        case "nav":
                            Send(NavFrame(int.Parse(p[1]), int.Parse(p[2]), int.Parse(p[3]), int.Parse(p[4]),
                                int.Parse(p[5]), int.Parse(p[6]), int.Parse(p[7])), "nav");
                            break;
                        case "stopnav":
                            Send(Frame(0xF1, 0x02, 0x02, new byte[] { 0x00 }), "stopnav");
                            break;
                        case "sweep":
                        {
                            int from = int.Parse(p[1]);
                            int to = int.Parse(p[2]);
                            int ms = p.Length > 3 ? int.Parse(p[3]) : 4000;
                            for (int code = from; code <= to; code++)
                            {
                                // afstand == code, zodat op het display af te lezen is welke code welke pijl geeft
                                Send(NavFrame(code, code, 0, 1, 0, 1, 9999), "sweep code " + code);
                                Say(">>> MANOEUVRECODE " + code + " staat nu op het display (afstand toont ook " + code + " m)");
                                Thread.Sleep(ms);
                            }
                            Say(">>> sweep klaar");
                            break;
                        }
                        default:
                            Say("onbekend commando");
                            break;
                    }
                }
                catch (Exception ex) { Say("CMD fout: " + ex.Message); }
            }
            Thread.Sleep(200);
        }
        Say("einde sessie");
        _log.Flush();
    }
}
