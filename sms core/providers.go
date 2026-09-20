// Package main — providers.go is the ONLY file that talks to upstream
// SMS providers (VoltX / MNIT / Zenex). The pool is flat: multiple keys
// for one provider act as multiple providers under round-robin.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Provider describes one upstream slot. Kind is "volt" (VoltX/MNIT dialect)
// or "zenex" (Zenex dialect).
type Provider struct {
	ID         string
	Name       string
	Kind       string
	Base       string
	WebBase    string
	AuthHeader string
	Key        string
}

var (
	poolMu  sync.Mutex
	pool    []Provider
	enabled = map[string]bool{}
	rr      = -1
	httpUp  = &http.Client{Timeout: 15 * time.Second}
)

func csv(v string) []string {
	var out []string
	for _, s := range strings.Split(v, ",") {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}

func countByName(name string) int {
	n := 0
	for _, p := range pool {
		if p.Name == name {
			n++
		}
	}
	return n
}

// InitPool builds the pool from env: VOLTX_API_KEY, MNIT_API_KEY (+MNIT_API_BASE),
// ZENEX_API_KEY (+ZENEX_API_BASE, +ZENEX_WEB_BASE). Comma-separated = several slots.
func InitPool() {
	for _, k := range csv(os.Getenv("VOLTX_API_KEY")) {
		pool = append(pool, Provider{
			ID: fmt.Sprintf("voltx:%d", countByName("voltx")), Name: "voltx", Kind: "volt",
			Base: "https://api.2oo9.cloud/MXS47FLFX0U/tnevs/@public/api", AuthHeader: "mauthapi", Key: k,
		})
	}
	mnitBase := os.Getenv("MNIT_API_BASE")
	if mnitBase == "" {
		mnitBase = "https://api.2oo9.cloud/MXS47FLFX0U/tnemn/@public/api"
	}
	for _, k := range csv(os.Getenv("MNIT_API_KEY")) {
		pool = append(pool, Provider{
			ID: fmt.Sprintf("mnit:%d", countByName("mnit")), Name: "mnit", Kind: "volt",
			Base: mnitBase, AuthHeader: "mauthapi", Key: k,
		})
	}
	zenBase := os.Getenv("ZENEX_API_BASE")
	if zenBase == "" {
		zenBase = "https://api.zenexnetwork.com"
	}
	zenWeb := os.Getenv("ZENEX_WEB_BASE")
	if zenWeb == "" {
		zenWeb = "https://www.zenexnetwork.com"
	}
	for _, k := range csv(os.Getenv("ZENEX_API_KEY")) {
		pool = append(pool, Provider{
			ID: fmt.Sprintf("zenex:%d", countByName("zenex")), Name: "zenex", Kind: "zenex",
			Base: zenBase, WebBase: zenWeb, AuthHeader: "mapikey", Key: k,
		})
	}
	for _, p := range pool {
		enabled[p.ID] = true
	}
}

// PoolStatus returns one entry per pool slot (key truncated).
func PoolStatus() []map[string]any {
	poolMu.Lock()
	defer poolMu.Unlock()
	out := []map[string]any{}
	for _, p := range pool {
		key6 := p.Key
		if len(key6) > 6 {
			key6 = key6[:6] + "..."
		}
		out = append(out, map[string]any{
			"id": p.ID, "name": p.Name, "kind": p.Kind,
			"enabled": enabled[p.ID], "key6": key6,
		})
	}
	return out
}

// SetEnabled replaces the enabled set; at least one known id must remain.
func SetEnabled(ids []string) error {
	poolMu.Lock()
	defer poolMu.Unlock()
	known := map[string]bool{}
	for _, p := range pool {
		known[p.ID] = true
	}
	next := map[string]bool{}
	for _, id := range ids {
		if known[id] {
			next[id] = true
		}
	}
	if len(next) == 0 {
		return fmt.Errorf("at least one pool entry must stay enabled")
	}
	enabled = next
	return nil
}

func enabledList() []Provider {
	poolMu.Lock()
	defer poolMu.Unlock()
	var list []Provider
	for _, p := range pool {
		if enabled[p.ID] {
			list = append(list, p)
		}
	}
	if len(list) == 0 && len(pool) > 0 {
		return pool[:1]
	}
	return list
}

// PickProvider round-robins over enabled slots.
func PickProvider() *Provider {
	list := enabledList()
	if len(list) == 0 {
		return nil
	}
	if len(list) == 1 {
		return &list[0]
	}
	poolMu.Lock()
	rr = (rr + 1) % len(list)
	p := list[rr]
	poolMu.Unlock()
	return &p
}

// AllProviders returns every slot (feed/OTP polling always uses all).
func AllProviders() []Provider {
	poolMu.Lock()
	defer poolMu.Unlock()
	out := make([]Provider, len(pool))
	copy(out, pool)
	return out
}

func postJSON(url, header, key string, body any) map[string]any {
	var buf bytes.Buffer
	_ = json.NewEncoder(&buf).Encode(body)
	req, err := http.NewRequest("POST", url, &buf)
	if err != nil {
		return map[string]any{}
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(header, key)
	res, err := httpUp.Do(req)
	if err != nil {
		return map[string]any{}
	}
	defer res.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(res.Body).Decode(&out)
	if out == nil {
		return map[string]any{}
	}
	return out
}

func getJSON(url, header, key string) map[string]any {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return map[string]any{}
	}
	req.Header.Set("Content-Type", "application/json")
	if header != "" {
		req.Header.Set(header, key)
	}
	res, err := httpUp.Do(req)
	if err != nil {
		return map[string]any{}
	}
	defer res.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(res.Body).Decode(&out)
	if out == nil {
		return map[string]any{}
	}
	return out
}

func metaCode(r map[string]any) float64 {
	if m, ok := r["meta"].(map[string]any); ok {
		if c, ok := m["code"].(float64); ok {
			return c
		}
	}
	return 0
}

func str(m map[string]any, k string) string {
	if s, ok := m[k].(string); ok {
		return s
	}
	return ""
}

// NumberData is a normalized provisioned number.
type NumberData struct {
	NoPlus  string
	Full    string
	Country string
}

// ProvisionNumber issues one number from a slot (live upstream call).
func ProvisionNumber(prov *Provider, rng string) *NumberData {
	if prov == nil {
		return nil
	}
	if prov.Kind == "zenex" {
		r := postJSON(prov.Base+"/v1/getnum", prov.AuthHeader, prov.Key,
			map[string]any{"range": rng, "is_national": false, "remove_plus": false})
		d, _ := r["data"].(map[string]any)
		if d == nil {
			return nil
		}
		if meta, ok := r["meta"].(map[string]any); ok {
			if c, ok := meta["code"].(float64); ok && c != 200 && str(meta, "status") != "success" {
				return nil
			}
		}
		full := str(d, "full_number")
		if full == "" {
			full = str(d, "number")
		}
		return &NumberData{NoPlus: strings.TrimPrefix(full, "+"), Full: full, Country: str(d, "country")}
	}
	rid := strings.Map(func(r rune) rune {
		if r == 'x' || r == 'X' {
			return -1
		}
		return r
	}, rng)
	r := postJSON(prov.Base+"/getnum", prov.AuthHeader, prov.Key, map[string]any{"rid": rid})
	if metaCode(r) != 200 {
		return nil
	}
	d, _ := r["data"].(map[string]any)
	if d == nil {
		return nil
	}
	return &NumberData{NoPlus: str(d, "no_plus_number"), Full: str(d, "full_number"), Country: str(d, "country")}
}

// FeedHit is one normalized live-feed row.
type FeedHit struct {
	Message  string
	Range    string
	Sid      string
	Time     int64
	App      string
	Method   string
	Provider string
}

// OtpHit is one normalized delivered-OTP row.
type OtpHit struct {
	Number   string // no plus
	Message  string
	Time     int64
	Provider string
}

var codeRe = regexp.MustCompile(`\b(\d{4,8})\b`)
var fbDashRe = regexp.MustCompile(`FB[- ]`)
var junkSidRe = regexp.MustCompile(`^(SMS|OTHER|UNKNOWN|\d+)$`)

// isJunkSid mirrors the bot's JUNK_SID filter, plus masked "***" services.
func isJunkSid(sid string) bool {
	s := strings.ToUpper(strings.TrimSpace(sid))
	return s == "" || strings.Contains(s, "*") || junkSidRe.MatchString(s)
}

// DetectSid mirrors detectSid() in the bot: brand from message text.
func DetectSid(msg string) string {
	m := strings.ToUpper(msg)
	switch {
	case strings.Contains(m, "MESSENGER"):
		return "MESSENGER"
	case strings.Contains(m, "FACEBOOK") || fbDashRe.MatchString(m):
		return "FACEBOOK"
	case strings.Contains(m, "INSTAGRAM"):
		return "INSTAGRAM"
	case strings.Contains(m, "WHATSAPP"):
		return "WHATSAPP"
	case strings.Contains(m, "TELEGRAM"):
		return "TELEGRAM"
	case strings.Contains(m, "DISCORD"):
		return "DISCORD"
	case strings.Contains(m, "TWITTER") || strings.Contains(m, " X "):
		return "TWITTER"
	case strings.Contains(m, "TIKTOK"):
		return "TIKTOK"
	case strings.Contains(m, "SNAPCHAT"):
		return "SNAPCHAT"
	case strings.Contains(m, "GOOGLE") || strings.Contains(m, "GMAIL"):
		return "GOOGLE"
	}
	return "SMS"
}

// CleanSid replaces junk/masked upstream service names with the
// message-detected brand, exactly like the working bot.
func CleanSid(sid, msg string) string {
	if isJunkSid(sid) {
		return DetectSid(msg)
	}
	return strings.ToUpper(strings.TrimSpace(sid))
}

// Classify mirrors the bot's classifyOTP: code + forgot/create + FB app tag.
func Classify(msg string) (app, method, code string) {
	code = codeRe.FindString(msg)
	method = "create"
	if len(code) == 6 || len(code) == 8 {
		method = "forgot"
	}
	app = "OTHER"
	switch {
	case regexp.MustCompile(`(?i)FB-`).MatchString(msg):
		app = "FB_WEB"
	case strings.Contains(msg, "H29Q+Fsn4Sr") || strings.Contains(msg, "H39Q+Fsn8Sr"):
		app = "FB_LITE"
	case strings.Contains(msg, "Laz+nxCarLW"):
		app = "FB_MAIN"
	case regexp.MustCompile(`(?i)Facebook Lite`).MatchString(msg):
		app = "FB_LITE"
	case regexp.MustCompile(`(?i)Facebook`).MatchString(msg):
		app = "FB_MAIN"
	}
	return app, method, code
}

func toInt64(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case int64:
		return t
	case string:
		if tm, err := time.Parse(time.RFC3339, strings.TrimSpace(t)); err == nil {
			return tm.UnixMilli()
		}
	}
	return 0
}

// FetchFeed pulls one slot's live feed (used by the background loop only).
func FetchFeed(prov Provider) []FeedHit {
	if prov.Kind == "zenex" {
		d := getJSON(prov.WebBase+"/api/v1/global-broadcast", "mapikey", prov.Key)
		arr, _ := d["data"].([]any)
		var out []FeedHit
		for _, it := range arr {
			m, _ := it.(map[string]any)
			if m == nil {
				continue
			}
			otp := str(m, "otp")
			app, method, _ := Classify(otp)
			svc := strings.ToUpper(strings.TrimSpace(str(m, "service")))
			if svc == "" {
				svc = "SMS"
			}
			tm := toInt64(m["time"])
			if tm == 0 {
				tm = time.Now().UnixMilli()
			}
			out = append(out, FeedHit{Message: otp, Range: str(m, "number"),
				Sid: CleanSid(svc, otp), Time: tm, App: app, Method: method, Provider: prov.ID})
		}
		return out
	}
	con := getJSON(prov.Base+"/console", prov.AuthHeader, prov.Key)
	data, _ := con["data"].(map[string]any)
	arr, _ := data["hits"].([]any)
	var out []FeedHit
	for _, it := range arr {
		m, _ := it.(map[string]any)
		if m == nil {
			continue
		}
		app, method, _ := Classify(str(m, "message"))
		out = append(out, FeedHit{Message: str(m, "message"), Range: str(m, "range"),
			Sid: CleanSid(str(m, "sid"), str(m, "message")), Time: toInt64(m["time"]),
			App: app, Method: method, Provider: prov.ID})
	}
	return out
}

// FetchOtps pulls one slot's delivered OTPs (background loop only).
func FetchOtps(prov Provider) []OtpHit {
	if prov.Kind == "zenex" {
		d := getJSON(prov.Base+"/v1/numsuccess/info", prov.AuthHeader, prov.Key)
		data, _ := d["data"].(map[string]any)
		arr, _ := data["otps"].([]any)
		var out []OtpHit
		for _, it := range arr {
			m, _ := it.(map[string]any)
			if m == nil {
				continue
			}
			out = append(out, OtpHit{Number: strings.TrimPrefix(str(m, "number"), "+"),
				Message: str(m, "otp"), Time: toInt64(m["created_at"]), Provider: prov.ID})
		}
		return out
	}
	d := getJSON(prov.Base+"/success-otp", prov.AuthHeader, prov.Key)
	data, _ := d["data"].(map[string]any)
	arr, _ := data["otps"].([]any)
	var out []OtpHit
	for _, it := range arr {
		m, _ := it.(map[string]any)
		if m == nil {
			continue
		}
		out = append(out, OtpHit{Number: str(m, "number"),
			Message: str(m, "message"), Time: toInt64(m["time"]), Provider: prov.ID})
	}
	return out
}

// AppLabel mirrors appLabel() in the bot: FB_LITE -> FB Lite.
func AppLabel(a string) string {
	switch a {
	case "FB_WEB":
		return "FB Web"
	case "FB_MAIN":
		return "FB Main"
	case "FB_LITE":
		return "FB Lite"
	case "IG_MAIN":
		return "Ig Main"
	case "IG_LITE":
		return "Ig Lite"
	case "IG_WEB":
		return "Ig Web"
	}
	return a
}

// MethodLabel mirrors methodLabel() in the bot: create -> Create New.
func MethodLabel(m string) string {
	if m == "create" {
		return "Create New"
	}
	return "Forgot Password"
}

// MaskMiddle hides one middle digit: the group-feed convention.
// Upstream feed rows are often range patterns (22896XXX) rather than full
// numbers, so patterns are shown grouped as-is instead of being mangled.
func MaskMiddle(full string) string {
	s := strings.TrimSpace(full)
	if strings.ContainsAny(s, "Xx") {
		return "+" + strings.Join(group3(s), " ")
	}
	digits := strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, s)
	if len(digits) < 5 {
		return "+" + s
	}
	i := len(digits) / 2
	m := digits[:i] + "X" + digits[i+1:]
	return "+" + strings.Join(group3(m), " ")
}

func group3(s string) []string {
	var out []string
	for i := 0; i < len(s); i += 3 {
		end := i + 3
		if end > len(s) {
			end = len(s)
		}
		out = append(out, s[i:end])
	}
	return out
}

// PrefixIsoFull is the world prefix table, ported from the bot.
var PrefixIsoFull = map[string]string{
	"1":   "CA",
	"7":   "RU",
	"20":  "EG",
	"27":  "ZA",
	"30":  "GR",
	"31":  "NL",
	"32":  "BE",
	"33":  "FR",
	"34":  "ES",
	"36":  "HU",
	"39":  "IT",
	"40":  "RO",
	"41":  "CH",
	"43":  "AT",
	"44":  "GB",
	"45":  "DK",
	"46":  "SE",
	"47":  "NO",
	"48":  "PL",
	"49":  "DE",
	"51":  "PE",
	"52":  "MX",
	"53":  "CU",
	"54":  "AR",
	"55":  "BR",
	"56":  "CL",
	"57":  "CO",
	"58":  "VE",
	"60":  "MY",
	"61":  "AU",
	"62":  "ID",
	"63":  "PH",
	"64":  "NZ",
	"65":  "SG",
	"66":  "TH",
	"81":  "JP",
	"82":  "KR",
	"84":  "VN",
	"86":  "CN",
	"90":  "TR",
	"91":  "IN",
	"92":  "PK",
	"93":  "AF",
	"94":  "LK",
	"95":  "MM",
	"98":  "IR",
	"211": "SS",
	"212": "MA",
	"213": "DZ",
	"216": "TN",
	"218": "LY",
	"220": "GM",
	"221": "SN",
	"222": "MR",
	"223": "ML",
	"224": "GN",
	"225": "CI",
	"226": "BF",
	"227": "NE",
	"228": "TG",
	"229": "BJ",
	"230": "MU",
	"231": "LR",
	"232": "SL",
	"233": "GH",
	"234": "NG",
	"235": "TD",
	"236": "CF",
	"237": "CM",
	"238": "CV",
	"239": "ST",
	"240": "GQ",
	"241": "GA",
	"242": "CG",
	"243": "CD",
	"244": "AO",
	"245": "GW",
	"246": "IO",
	"247": "AC",
	"248": "SC",
	"249": "SD",
	"250": "RW",
	"251": "ET",
	"252": "SO",
	"253": "DJ",
	"254": "KE",
	"255": "TZ",
	"256": "UG",
	"257": "BI",
	"258": "MZ",
	"260": "ZM",
	"261": "MG",
	"262": "RE",
	"263": "ZW",
	"264": "NA",
	"265": "MW",
	"266": "LS",
	"267": "BW",
	"268": "SZ",
	"269": "KM",
	"290": "SH",
	"291": "ER",
	"297": "AW",
	"298": "FO",
	"299": "GL",
	"350": "GI",
	"351": "PT",
	"352": "LU",
	"353": "IE",
	"354": "IS",
	"355": "AL",
	"356": "MT",
	"357": "CY",
	"358": "FI",
	"359": "BG",
	"370": "LT",
	"371": "LV",
	"372": "EE",
	"373": "MD",
	"374": "AM",
	"375": "BY",
	"376": "AD",
	"377": "MC",
	"378": "SM",
	"379": "VA",
	"380": "UA",
	"381": "RS",
	"382": "ME",
	"383": "XK",
	"385": "HR",
	"386": "SI",
	"387": "BA",
	"389": "MK",
	"420": "CZ",
	"421": "SK",
	"423": "LI",
	"500": "FK",
	"501": "BZ",
	"502": "GT",
	"503": "SV",
	"504": "HN",
	"505": "NI",
	"506": "CR",
	"507": "PA",
	"508": "PM",
	"509": "HT",
	"590": "GP",
	"591": "BO",
	"592": "GY",
	"593": "EC",
	"594": "GF",
	"595": "PY",
	"596": "MQ",
	"597": "SR",
	"598": "UY",
	"599": "BQ",
	"670": "TL",
	"672": "NF",
	"673": "BN",
	"674": "NR",
	"675": "PG",
	"676": "TO",
	"677": "SB",
	"678": "VU",
	"679": "FJ",
	"680": "PW",
	"681": "WF",
	"682": "CK",
	"683": "NU",
	"684": "AS",
	"685": "WS",
	"686": "KI",
	"687": "NC",
	"688": "TV",
	"689": "PF",
	"690": "TK",
	"691": "FM",
	"692": "MH",
	"850": "KP",
	"852": "HK",
	"853": "MO",
	"855": "KH",
	"856": "LA",
	"870": "PN",
	"880": "BD",
	"886": "TW",
	"960": "MV",
	"961": "LB",
	"962": "JO",
	"963": "SY",
	"964": "IQ",
	"965": "KW",
	"966": "SA",
	"967": "YE",
	"968": "OM",
	"970": "PS",
	"971": "AE",
	"972": "IL",
	"973": "BH",
	"974": "QA",
	"975": "BT",
	"976": "MN",
	"977": "NP",
	"992": "TJ",
	"993": "TM",
	"994": "AZ",
	"995": "GE",
	"996": "KG",
	"998": "UZ",
}

// IsoFromPrefix returns the ISO code for a dial prefix (3, 2, then 1 digits).
func IsoFromPrefix(prefix string) string {
	for _, l := range []int{4, 3, 2, 1} {
		if len(prefix) >= l {
			if iso, ok := PrefixIsoFull[prefix[:l]]; ok {
				return iso
			}
		}
	}
	return ""
}
