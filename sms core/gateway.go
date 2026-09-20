// Command gateway — KiloSMS API gateway (Railway). Go stdlib only.
// App auth:    Authorization: Bearer $KILO_API_KEY  -> /v1/feed, /v1/numbers, /v1/otp, /v1/meta
// Admin auth:  Authorization: Bearer $ADMIN_KEY     -> /v1/admin/*
// Upstream provider keys never leave this process (see providers.go).
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

var (
	cacheMu     sync.Mutex
	feedCache   []FeedHit
	otpCache    []OtpHit
	injectCache []OtpHit // test-injected SMS; merged into otpCache on every refresh
	lastFeedAt  int64
	lastOtpAt   int64
	reqTotal    int64
	reqByRoute  = map[string]int64{}
)

func writeJSON(w http.ResponseWriter, code int, v any) {
	b, _ := json.Marshal(v)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

func bearer(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if len(h) > 7 && strings.EqualFold(h[:7], "Bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return ""
}

func needApp(r *http.Request) bool {
	key := os.Getenv("KILO_API_KEY")
	if key == "" {
		return true // open in local dev
	}
	return bearer(r) == key
}

func needAdmin(r *http.Request) bool {
	key := os.Getenv("ADMIN_KEY")
	if key == "" {
		return true
	}
	return bearer(r) == key
}

func refreshFeed() {
	var all []FeedHit
	for _, p := range AllProviders() {
		func() {
			defer func() { _ = recover() }()
			all = append(all, FetchFeed(p)...)
		}()
	}
	cutoff := time.Now().UnixMilli() - 5*60*1000
	// Dedupe by range+code, keeping the FIRST-seen time: upstreams re-list
	// the same OTP on later polls, which used to make old rows look new.
	seen := map[string]FeedHit{}
	order := []string{}
	for _, h := range all {
		if h.Time < cutoff {
			continue
		}
		key := h.Range + "|" + h.Message
		if prev, ok := seen[key]; !ok || h.Time < prev.Time {
			if !ok {
				order = append(order, key)
			}
			seen[key] = h
		}
	}
	var kept []FeedHit
	for _, k := range order {
		kept = append(kept, seen[k])
	}
	sort.Slice(kept, func(i, j int) bool { return kept[i].Time > kept[j].Time })
	if len(kept) > 200 {
		kept = kept[:200]
	}
	cacheMu.Lock()
	feedCache = kept
	lastFeedAt = time.Now().UnixMilli()
	cacheMu.Unlock()
}

func refreshOtps() {
	var all []OtpHit
	for _, p := range AllProviders() {
		func() {
			defer func() { _ = recover() }()
			all = append(all, FetchOtps(p)...)
		}()
	}
	cutoff := time.Now().UnixMilli() - 10*60*1000
	var kept []OtpHit
	for _, o := range all {
		if o.Time >= cutoff {
			kept = append(kept, o)
		}
	}
	cacheMu.Lock()
	now := time.Now().UnixMilli()
	var injKept []OtpHit
	for _, o := range injectCache {
		if now-o.Time < 10*60*1000 {
			injKept = append(injKept, o)
			kept = append(kept, o)
		}
	}
	injectCache = injKept
	sort.Slice(kept, func(i, j int) bool { return kept[i].Time > kept[j].Time })
	if len(kept) > 500 {
		kept = kept[:500]
	}
	otpCache = kept
	lastOtpAt = now
	cacheMu.Unlock()
	pushFreshOtps(kept)
}

// --- SSE push: one stream per app, keyed by subscribed numbers ---------

var (
	subMu    sync.Mutex
	subs     = map[string]map[chan []byte]struct{}{}
	lastPush int64
)

func pushFreshOtps(kept []OtpHit) {
	var fresh []OtpHit
	cacheMu.Lock()
	for _, o := range kept {
		if o.Time > lastPush {
			fresh = append(fresh, o)
		}
	}
	for _, o := range fresh {
		if o.Time > lastPush {
			lastPush = o.Time
		}
	}
	cacheMu.Unlock()
	for _, o := range fresh {
		_, _, code := Classify(o.Message)
		body, _ := json.Marshal(map[string]any{
			"number": digitsOnly(o.Number), "code": code,
			"text": o.Message, "at": o.Time,
		})
		event := append([]byte("event: otp\ndata: "), body...)
		event = append(event, '\n', '\n')
		subMu.Lock()
		for ch := range subs[digitsOnly(o.Number)] {
			select {
			case ch <- event:
			default: // slow reader: drop, it still has polling fallback
			}
		}
		subMu.Unlock()
	}
}

func subscribe(numbers []string) (chan []byte, func()) {
	ch := make(chan []byte, 8)
	subMu.Lock()
	for _, n := range numbers {
		n = digitsOnly(n)
		if n == "" {
			continue
		}
		set := subs[n]
		if set == nil {
			set = map[chan []byte]struct{}{}
			subs[n] = set
		}
		set[ch] = struct{}{}
	}
	subMu.Unlock()
	return ch, func() {
		subMu.Lock()
		for set := range subs {
			delete(subs[set], ch)
			if len(subs[set]) == 0 {
				delete(subs, set)
			}
		}
		subMu.Unlock()
	}
}

// handleStream serves GET /v1/stream?numbers=a,b,c as SSE: replays cached
// OTPs for the subscribed numbers, then pushes new ones as they arrive.
// Heartbeat comment every 25s keeps NAT/proxies from idling out.
func handleStream(w http.ResponseWriter, r *http.Request) {
	numbers := strings.Split(r.URL.Query().Get("numbers"), ",")
	ch, unsub := subscribe(numbers)
	defer unsub()

	cacheMu.Lock()
	var replay []OtpHit
	for _, o := range otpCache {
		for _, n := range numbers {
			if n != "" && digitsOnly(o.Number) == digitsOnly(n) {
				replay = append(replay, o)
			}
		}
	}
	cacheMu.Unlock()
	sort.Slice(replay, func(i, j int) bool { return replay[i].Time < replay[j].Time })

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeJSON(w, 500, map[string]any{"ok": false, "error": "streaming unsupported"})
		return
	}
	for _, o := range replay {
		_, _, code := Classify(o.Message)
		body, _ := json.Marshal(map[string]any{
			"number": digitsOnly(o.Number), "code": code,
			"text": o.Message, "at": o.Time,
		})
		_, _ = w.Write(append(append([]byte("event: otp\ndata: "), body...), '\n', '\n'))
	}
	flusher.Flush()

	beat := time.NewTicker(25 * time.Second)
	defer beat.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case ev := <-ch:
			if _, err := w.Write(ev); err != nil {
				return
			}
			flusher.Flush()
		case <-beat.C:
			if _, err := w.Write([]byte(":ping\n\n")); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}

var rangeRe = regexp.MustCompile(`[^0-9X]`)

func handleHealth(w http.ResponseWriter, r *http.Request) {
	cacheMu.Lock()
	defer cacheMu.Unlock()
	writeJSON(w, 200, map[string]any{
		"ok": true, "feed": len(feedCache), "otps": len(otpCache),
		"lastFeedAt": lastFeedAt, "lastOtpAt": lastOtpAt,
	})
}

func handleFeed(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	var since int64
	_, _ = fmt.Sscan(q.Get("since"), &since)
	limit := 20
	_, _ = fmt.Sscan(q.Get("limit"), &limit)
	if limit < 1 || limit > 50 {
		limit = 20
	}
	cacheMu.Lock()
	defer cacheMu.Unlock()
	var items []map[string]any
	for _, h := range feedCache {
		if h.Time <= since {
			continue
		}
		app, method, code := h.App, h.Method, ""
		if _, m, c := Classify(h.Message); c != "" {
			code = c
			if h.Method == "" {
				method = m
			}
		}
		if h.App == "" {
			app, _, _ = Classify(h.Message)
		}
		svc := CleanSid(h.Sid, h.Message)
		if svc == "" {
			svc = "SMS"
		}
		items = append(items, map[string]any{
			"masked": MaskMiddle(h.Range), "svc": svc, "method": method, "app": app,
			"appLabel": AppLabel(app), "methodLabel": MethodLabel(method),
			"iso":  IsoFromPrefix(digitsOnly(h.Range)),
			"code": code, "msg": h.Message, "range": h.Range, "at": h.Time,
		})
		if len(items) >= limit {
			break
		}
	}
	if items == nil {
		items = []map[string]any{}
	}
	writeJSON(w, 200, map[string]any{"ok": true, "items": items, "now": time.Now().UnixMilli()})
}

func handleNumbers(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Range string `json:"range"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	rng := strings.ToUpper(rangeRe.ReplaceAllString(body.Range, ""))
	if len(rng) > 15 {
		rng = rng[:15]
	}
	if len(rng) < 3 {
		writeJSON(w, 400, map[string]any{"ok": false, "error": "range like 229016XXX required"})
		return
	}
	prov := PickProvider()
	data := ProvisionNumber(prov, rng)
	if data == nil || data.NoPlus == "" {
		writeJSON(w, 502, map[string]any{"ok": false, "error": "provider returned nothing, retry"})
		return
	}
	full := strings.TrimPrefix(data.NoPlus, "+")
	var disp strings.Builder
	for i, ch := range full {
		if i > 0 && i%3 == 0 {
			disp.WriteByte(' ')
		}
		disp.WriteRune(ch)
	}
	country := data.Country
	if country == "" {
		country = "Unknown"
	}
	provID := ""
	if prov != nil {
		provID = prov.ID
	}
	writeJSON(w, 200, map[string]any{"ok": true, "number": map[string]any{
		"full": full, "display": "+" + disp.String(), "country": country,
		"range": rng, "expires_in": 420, "provider": provID,
	}, "now": time.Now().UnixMilli()})
}

func digitsOnly(s string) string {
	return strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, s)
}

func handleOtp(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	number := digitsOnly(strings.TrimPrefix(q.Get("number"), "+"))
	if number == "" {
		writeJSON(w, 400, map[string]any{"ok": false, "error": "number required"})
		return
	}
	var since int64
	_, _ = fmt.Sscan(q.Get("since"), &since)
	cacheMu.Lock()
	defer cacheMu.Unlock()
	var msgs []map[string]any
	for _, o := range otpCache {
		if digitsOnly(o.Number) != number || o.Time <= since {
			continue
		}
		_, _, code := Classify(o.Message)
		msgs = append(msgs, map[string]any{"code": code, "text": o.Message, "at": o.Time})
	}
	sort.Slice(msgs, func(i, j int) bool { return msgs[i]["at"].(int64) < msgs[j]["at"].(int64) })
	if msgs == nil {
		msgs = []map[string]any{}
	}
	var latest any
	if len(msgs) > 0 {
		latest = msgs[len(msgs)-1]["code"]
	}
	writeJSON(w, 200, map[string]any{"ok": true, "code": latest, "msgs": msgs, "now": time.Now().UnixMilli()})
}

// handleInject is POST /v1/admin/inject — test-only fake SMS delivery.
// The hit lives in injectCache (~10 min, survives refreshOtps) and is
// pushed to SSE subscribers, so the app shows it like a real OTP.
func handleInject(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Number string `json:"number"`
		Code   string `json:"code"`
		Text   string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, map[string]any{"ok": false, "error": "bad json"})
		return
	}
	number := digitsOnly(strings.TrimPrefix(strings.TrimSpace(body.Number), "+"))
	if number == "" {
		writeJSON(w, 400, map[string]any{"ok": false, "error": "number required"})
		return
	}
	text := strings.TrimSpace(body.Text)
	code := strings.TrimSpace(body.Code)
	if text == "" && code != "" {
		text = code + " is your Facebook confirmation code"
	}
	if code == "" {
		_, _, code = Classify(text)
	}
	hit := OtpHit{Number: number, Message: text, Time: time.Now().UnixMilli(), Provider: "inject"}
	cacheMu.Lock()
	injectCache = append(injectCache, hit)
	otpCache = append([]OtpHit{hit}, otpCache...)
	if len(otpCache) > 500 {
		otpCache = otpCache[:500]
	}
	cacheMu.Unlock()
	pushFreshOtps([]OtpHit{hit})
	writeJSON(w, 200, map[string]any{"ok": true, "number": number, "code": code})
}

func handleMeta(w http.ResponseWriter, r *http.Request) {
	cacheMu.Lock()
	defer cacheMu.Unlock()
	type agg struct {
		prefix   string
		count    int
		services map[string]bool
		ranges   map[string]int
	}
	cm := map[string]*agg{}
	svcCount := map[string]int{}
	for _, h := range feedCache {
		if h.Range == "" {
			continue
		}
		p3 := h.Range
		if len(p3) > 3 {
			p3 = p3[:3]
		}
		a := cm[p3]
		if a == nil {
			a = &agg{prefix: p3, services: map[string]bool{}, ranges: map[string]int{}}
			cm[p3] = a
		}
		a.count++
		a.ranges[h.Range]++
		if h.Sid != "" {
			svc := strings.ToUpper(strings.TrimSpace(h.Sid))
			a.services[svc] = true
			svcCount[svc]++
		}
	}
	var countries []map[string]any
	for _, a := range cm {
		var svcs []string
		for s := range a.services {
			svcs = append(svcs, s)
		}
		sort.Strings(svcs)
		topRange, topN := "", 0
		for r, n := range a.ranges {
			if n > topN {
				topRange, topN = r, n
			}
		}
		countries = append(countries, map[string]any{
			"prefix": a.prefix, "count": a.count, "services": svcs, "range": topRange,
		})
	}
	sort.Slice(countries, func(i, j int) bool { return countries[i]["count"].(int) > countries[j]["count"].(int) })
	var services []map[string]any
	for n, c := range svcCount {
		services = append(services, map[string]any{"name": n, "count": c})
	}
	sort.Slice(services, func(i, j int) bool { return services[i]["count"].(int) > services[j]["count"].(int) })
	if len(services) > 20 {
		services = services[:20]
	}
	if countries == nil {
		countries = []map[string]any{}
	}
	if services == nil {
		services = []map[string]any{}
	}
	writeJSON(w, 200, map[string]any{
		"ok": true, "countries": countries, "services": services, "now": time.Now().UnixMilli(),
	})
}

func handlePoolGet(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"ok": true, "pool": PoolStatus()})
}

func handlePoolPut(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Enabled []string `json:"enabled"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	if err := SetEnabled(body.Enabled); err != nil {
		writeJSON(w, 400, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "pool": PoolStatus()})
}

func handleStats(w http.ResponseWriter, r *http.Request) {
	cacheMu.Lock()
	defer cacheMu.Unlock()
	writeJSON(w, 200, map[string]any{
		"ok": true, "reqTotal": reqTotal, "reqByRoute": reqByRoute,
		"feed": len(feedCache), "otps": len(otpCache),
		"lastFeedAt": lastFeedAt, "lastOtpAt": lastOtpAt, "pool": PoolStatus(),
	})
}

func main() {
	InitPool()
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	mux := http.NewServeMux()
	wrap := func(h func(http.ResponseWriter, *http.Request), admin bool) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			cacheMu.Lock()
			reqTotal++
			reqByRoute[r.URL.Path]++
			cacheMu.Unlock()
			if admin {
				if !needAdmin(r) {
					writeJSON(w, 401, map[string]any{"ok": false, "error": "bad admin key"})
					return
				}
			} else if r.URL.Path != "/v1/health" && !needApp(r) {
				writeJSON(w, 401, map[string]any{"ok": false, "error": "bad api key"})
				return
			}
			h(w, r)
		}
	}
	mux.HandleFunc("/v1/health", wrap(handleHealth, false))
	mux.HandleFunc("/v1/feed", wrap(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
			return
		}
		handleFeed(w, r)
	}, false))
	mux.HandleFunc("/v1/numbers", wrap(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
			return
		}
		handleNumbers(w, r)
	}, false))
	mux.HandleFunc("/v1/otp", wrap(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
			return
		}
		handleOtp(w, r)
	}, false))
	mux.HandleFunc("/v1/meta", wrap(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
			return
		}
		handleMeta(w, r)
	}, false))
	mux.HandleFunc("/v1/stream", wrap(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
			return
		}
		handleStream(w, r)
	}, false))
	mux.HandleFunc("/v1/admin/pool", wrap(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case "GET":
			handlePoolGet(w, r)
		case "PUT":
			handlePoolPut(w, r)
		default:
			writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
		}
	}, true))
	mux.HandleFunc("/v1/admin/stats", wrap(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
			return
		}
		handleStats(w, r)
	}, true))
	mux.HandleFunc("/v1/admin/inject", wrap(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
			return
		}
		handleInject(w, r)
	}, true))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 404, map[string]any{"ok": false, "error": "unknown route"})
	})

	go refreshFeed()
	go refreshOtps()
	go func() {
		for range time.Tick(60 * time.Second) {
			refreshFeed()
		}
	}()
	go func() {
		for range time.Tick(5 * time.Second) {
			refreshOtps()
		}
	}()

	names := []string{}
	for _, p := range AllProviders() {
		names = append(names, p.ID)
	}
	if len(names) == 0 {
		names = []string{"EMPTY (set VOLTX_API_KEY)"}
	}
	log.Printf("[gateway] listening on :%s | pool=%v", port, names)
	log.Fatal(http.ListenAndServe(":"+port, mux))
}
