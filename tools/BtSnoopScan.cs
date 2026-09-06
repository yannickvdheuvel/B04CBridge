using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Text;

// Haalt B04C-protocolframes uit een Android Bluetooth HCI snoop log.
//
// Bedoeld om te zien wat de originele BIKEGO-app naar het display stuurt: functies die wij
// nog niet kennen (verlichting, lock, assist wijzigen, anti-diefstal) verraden zichzelf als
// frames die wij nog nooit verstuurd hebben.
//
// Het zware Bluetooth-werk kunnen we overslaan. Onze frames beginnen altijd met 55 AA, byte 3
// zegt zelf welke kant het op gaat, en de checksum aan het eind filtert toevallige treffers
// vrijwel volledig weg. Dus: btsnoop-records uitpakken voor de tijdstempels, en in elk record
// zoeken naar geldige frames.
//
// Gebruik:  BtSnoopScan.exe <bugreport.zip | btsnoop_hci.log> [--all]
//           --all toont ook de telemetrie die het display uit zichzelf blijft herhalen
class BtSnoopScan
{
    // btsnoop-tijdstempels tellen microseconden vanaf jaar 0; dit is 1970-01-01 in die eenheid.
    const long EPOCH_US = 62168256000000000L;

    static bool _showAll;
    static int _frames;
    static readonly Dictionary<string, int> _counts = new Dictionary<string, int>();
    static readonly Dictionary<string, string> _firstPayload = new Dictionary<string, string>();

    static void Main(string[] args)
    {
        if (args.Length < 1)
        {
            Console.WriteLine("gebruik: BtSnoopScan.exe <bugreport.zip | btsnoop_hci.log> [--all]");
            return;
        }
        foreach (string a in args) if (a == "--all") _showAll = true;
        string path = args[0];

        if (!File.Exists(path)) { Console.WriteLine("bestand niet gevonden: " + path); return; }

        if (path.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
        {
            bool found = false;
            using (var zip = ZipFile.OpenRead(path))
            {
                foreach (var e in zip.Entries)
                {
                    if (e.FullName.IndexOf("btsnoop", StringComparison.OrdinalIgnoreCase) < 0) continue;
                    found = true;
                    Console.WriteLine("=== " + e.FullName + "  (" + e.Length + " bytes) ===");
                    using (var s = e.Open()) ScanStream(ReadAll(s));
                }
            }
            if (!found) Console.WriteLine("geen btsnoop-bestand in de zip; staat 'Bluetooth HCI snoop log' wel aan?");
        }
        else ScanStream(File.ReadAllBytes(path));

        Console.WriteLine();
        Console.WriteLine("=== samenvatting: " + _frames + " frames ===");
        var keys = new List<string>(_counts.Keys);
        keys.Sort();
        foreach (string k in keys)
            Console.WriteLine(string.Format("{0}  {1,6}x   eerste payload: {2}", k, _counts[k], _firstPayload[k]));
    }

    static byte[] ReadAll(Stream s)
    {
        using (var ms = new MemoryStream()) { s.CopyTo(ms); return ms.ToArray(); }
    }

    static uint BE32(byte[] b, int i)
    {
        return ((uint)b[i] << 24) | ((uint)b[i + 1] << 16) | ((uint)b[i + 2] << 8) | b[i + 3];
    }
    static long BE64(byte[] b, int i)
    {
        long v = 0;
        for (int k = 0; k < 8; k++) v = (v << 8) | b[i + k];
        return v;
    }

    static void ScanStream(byte[] data)
    {
        // Zonder geldige btsnoop-header het hele bestand als één blok doorzoeken.
        if (data.Length < 16 || Encoding.ASCII.GetString(data, 0, 7) != "btsnoop")
        {
            Console.WriteLine("(geen btsnoop-header; ruwe scan zonder tijdstempels)");
            ScanPayload(data, 0, data.Length, DateTime.MinValue, false);
            return;
        }

        int pos = 16;
        while (pos + 24 <= data.Length)
        {
            int inclLen = (int)BE32(data, pos + 4);
            uint flags = BE32(data, pos + 8);
            long ts = BE64(data, pos + 16);
            pos += 24;
            if (inclLen < 0 || pos + inclLen > data.Length) break;

            // flags bit 0: 1 = van controller naar host (ontvangen), 0 = verzonden
            bool received = (flags & 1) != 0;
            DateTime when = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)
                .AddTicks((ts - EPOCH_US) * 10).ToLocalTime();

            ScanPayload(data, pos, inclLen, when, received);
            pos += inclLen;
        }
    }

    static void ScanPayload(byte[] d, int start, int len, DateTime when, bool received)
    {
        int end = start + len;
        for (int i = start; i + 9 <= end; i++)
        {
            if (d[i] != 0x55 || d[i + 1] != 0xAA) continue;
            int plen = d[i + 2];
            int total = 9 + plen;
            if (i + total > end) continue;

            int sum = 0;
            for (int k = 0; k < total - 2; k++) sum += d[i + k];
            int cs1 = (0xFE - (sum & 0xFF)) & 0xFF;
            int cs2 = (0x100 - ((sum >> 8) & 0xFF)) & 0xFF;
            if (d[i + total - 2] != cs1 || d[i + total - 1] != cs2) continue;   // toevallige 55 AA

            int dir = d[i + 3], tgt = d[i + 4], sub = d[i + 5], par = d[i + 6];
            bool toDisplay = dir == 0x11;
            string key = string.Format("{0} {1:X2}/{2:X2}/{3:X2}", toDisplay ? "APP->B04C" : "B04C->APP", tgt, sub, par);

            var payload = new StringBuilder();
            for (int k = 7; k < total - 2; k++)
            {
                if (payload.Length > 0) payload.Append(' ');
                payload.Append(d[i + k].ToString("X2"));
            }
            string pay = payload.ToString();

            _frames++;
            if (!_counts.ContainsKey(key)) { _counts[key] = 0; _firstPayload[key] = pay; }
            _counts[key]++;

            // De telemetrie die het display uit zichzelf blijft sturen is bekend en zou de
            // interessante commando's wegdrukken.
            bool noise = !toDisplay && tgt == 0x11 && sub == 0x06;
            if (noise && !_showAll) { i += total - 1; continue; }

            string stamp = when == DateTime.MinValue ? "" : when.ToString("HH:mm:ss.fff") + " ";
            Console.WriteLine(stamp + key + "  " + pay + Note(toDisplay, tgt, sub, par));
            i += total - 1;
        }
    }

    static string Note(bool toDisplay, int tgt, int sub, int par)
    {
        if (toDisplay && tgt == 0x10 && sub == 0x01) return "   <- challenge opgevraagd";
        if (toDisplay && tgt == 0x10 && sub == 0x20) return "   <- authenticatie";
        if (toDisplay && tgt == 0x10 && sub == 0x02 && par == 0x3E) return "   <- tijd zetten";
        if (toDisplay && tgt == 0xF1 && sub == 0x03) return "   <- navigatie";
        if (toDisplay && tgt == 0xF1 && sub == 0x02) return "   <- navigatie stoppen";
        if (toDisplay) return "   <- ONBEKEND COMMANDO";
        return "";
    }
}
