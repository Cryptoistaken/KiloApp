// Exit-IP check route — blended in from checker/src/index.ts (Cloudflare
// Worker `kiloip`). Here it runs natively on the edge: request.cf exists,
// so no IP-lookup API is needed. Contract is unchanged: GET -> 200 JSON
// with exactly ip, countryCode, country, regionName, city, isp, org,
// asName, timezone (empty string when Cloudflare has no value); no-store.
//
// NOTE: the Android app still calls the standalone kiloip URL. Pointing it
// at /v1/check here is a separate app-side change + release.

import { Hono } from "hono";
import type { EdgeEnv } from "../lib/edge";

function cfStr(cf: unknown, key: string): string {
  if (typeof cf !== "object" || cf === null) return "";
  const v: unknown = (cf as Record<string, unknown>)[key];
  return typeof v === "string" ? v : "";
}

function cfAsn(cf: unknown): string {
  if (typeof cf !== "object" || cf === null) return "";
  const v: unknown = (cf as Record<string, unknown>)["asn"];
  if (typeof v === "number") return "AS" + v;
  if (typeof v === "string" && v.length > 0) return v.startsWith("AS") ? v : "AS" + v;
  return "";
}

// ISO-3166 alpha-2 -> short English name. Cloudflare only sends the code;
// the map keeps the endpoint a complete ip-api replacement. XX/T1 are
// Cloudflare's own "unknown" / "Tor exit" markers.
const COUNTRY_NAMES: Record<string, string> = {
  AD: "Andorra", AE: "United Arab Emirates", AF: "Afghanistan", AG: "Antigua and Barbuda",
  AI: "Anguilla", AL: "Albania", AM: "Armenia", AO: "Angola", AQ: "Antarctica",
  AR: "Argentina", AS: "American Samoa", AT: "Austria", AU: "Australia", AW: "Aruba",
  AX: "Aland Islands", AZ: "Azerbaijan", BA: "Bosnia and Herzegovina", BB: "Barbados",
  BD: "Bangladesh", BE: "Belgium", BF: "Burkina Faso", BG: "Bulgaria", BH: "Bahrain",
  BI: "Burundi", BJ: "Benin", BL: "Saint Barthelemy", BM: "Bermuda", BN: "Brunei",
  BO: "Bolivia", BQ: "Bonaire Sint Eustatius and Saba", BR: "Brazil", BS: "Bahamas",
  BT: "Bhutan", BV: "Bouvet Island", BW: "Botswana", BY: "Belarus", BZ: "Belize",
  CA: "Canada", CC: "Cocos Islands", CD: "DR Congo", CF: "Central African Republic",
  CG: "Republic of the Congo", CH: "Switzerland", CI: "Ivory Coast", CK: "Cook Islands",
  CL: "Chile", CM: "Cameroon", CN: "China", CO: "Colombia", CR: "Costa Rica",
  CU: "Cuba", CV: "Cape Verde", CW: "Curacao", CX: "Christmas Island", CY: "Cyprus",
  CZ: "Czechia", DE: "Germany", DJ: "Djibouti", DK: "Denmark", DM: "Dominica",
  DO: "Dominican Republic", DZ: "Algeria", EC: "Ecuador", EE: "Estonia", EG: "Egypt",
  EH: "Western Sahara", ER: "Eritrea", ES: "Spain", ET: "Ethiopia", FI: "Finland",
  FJ: "Fiji", FK: "Falkland Islands", FM: "Micronesia", FO: "Faroe Islands",
  FR: "France", GA: "Gabon", GB: "United Kingdom", GD: "Grenada", GE: "Georgia",
  GF: "French Guiana", GG: "Guernsey", GH: "Ghana", GI: "Gibraltar", GL: "Greenland",
  GM: "Gambia", GN: "Guinea", GP: "Guadeloupe", GQ: "Equatorial Guinea", GR: "Greece",
  GS: "South Georgia", GT: "Guatemala", GU: "Guam", GW: "Guinea-Bissau", GY: "Guyana",
  HK: "Hong Kong", HM: "Heard Island and McDonald Islands", HN: "Honduras", HR: "Croatia",
  HT: "Haiti", HU: "Hungary", ID: "Indonesia", IE: "Ireland", IL: "Israel",
  IM: "Isle of Man", IN: "India", IO: "British Indian Ocean Territory", IQ: "Iraq",
  IR: "Iran", IS: "Iceland", IT: "Italy", JE: "Jersey", JM: "Jamaica", JO: "Jordan",
  JP: "Japan", KE: "Kenya", KG: "Kyrgyzstan", KH: "Cambodia", KI: "Kiribati",
  KM: "Comoros", KN: "Saint Kitts and Nevis", KP: "North Korea", KR: "South Korea",
  KW: "Kuwait", KY: "Cayman Islands", KZ: "Kazakhstan", LA: "Laos", LB: "Lebanon",
  LC: "Saint Lucia", LI: "Liechtenstein", LK: "Sri Lanka", LR: "Liberia", LS: "Lesotho",
  LT: "Lithuania", LU: "Luxembourg", LV: "Latvia", LY: "Libya", MA: "Morocco",
  MC: "Monaco", MD: "Moldova", ME: "Montenegro", MF: "Saint Martin", MG: "Madagascar",
  MH: "Marshall Islands", MK: "North Macedonia", ML: "Mali", MM: "Myanmar", MN: "Mongolia",
  MO: "Macao", MP: "Northern Mariana Islands", MQ: "Martinique", MR: "Mauritania",
  MS: "Montserrat", MT: "Malta", MU: "Mauritius", MV: "Maldives", MW: "Malawi",
  MX: "Mexico", MY: "Malaysia", MZ: "Mozambique", NA: "Namibia", NC: "New Caledonia",
  NE: "Niger", NF: "Norfolk Island", NG: "Nigeria", NI: "Nicaragua", NL: "Netherlands",
  NO: "Norway", NP: "Nepal", NR: "Nauru", NU: "Niue", NZ: "New Zealand", OM: "Oman",
  PA: "Panama", PE: "Peru", PF: "French Polynesia", PG: "Papua New Guinea",
  PH: "Philippines", PK: "Pakistan", PL: "Poland", PM: "Saint Pierre and Miquelon",
  PN: "Pitcairn Islands", PR: "Puerto Rico", PS: "Palestine", PT: "Portugal",
  PW: "Palau", PY: "Paraguay", QA: "Qatar", RE: "Reunion", RO: "Romania",
  RS: "Serbia", RU: "Russia", RW: "Rwanda", SA: "Saudi Arabia", SB: "Solomon Islands",
  SC: "Seychelles", SD: "Sudan", SE: "Sweden", SG: "Singapore", SH: "Saint Helena",
  SI: "Slovenia", SJ: "Svalbard and Jan Mayen", SK: "Slovakia", SL: "Sierra Leone",
  SM: "San Marino", SN: "Senegal", SO: "Somalia", SR: "Suriname", SS: "South Sudan",
  ST: "Sao Tome and Principe", SV: "El Salvador", SX: "Sint Maarten", SY: "Syria",
  SZ: "Eswatini", TC: "Turks and Caicos Islands", TD: "Chad", TF: "French Southern Territories",
  TG: "Togo", TH: "Thailand", TJ: "Tajikistan", TK: "Tokelau", TL: "Timor-Leste",
  TM: "Turkmenistan", TN: "Tunisia", TO: "Tonga", TR: "Turkey", TT: "Trinidad and Tobago",
  TV: "Tuvalu", TW: "Taiwan", TZ: "Tanzania", UA: "Ukraine", UG: "Uganda",
  UM: "US Minor Outlying Islands", US: "United States", UY: "Uruguay", UZ: "Uzbekistan",
  VA: "Vatican City", VC: "Saint Vincent and the Grenadines", VE: "Venezuela",
  VG: "British Virgin Islands", VI: "US Virgin Islands", VN: "Vietnam", VU: "Vanuatu",
  WF: "Wallis and Futuna", WS: "Samoa", XK: "Kosovo", YE: "Yemen", YT: "Mayotte",
  ZA: "South Africa", ZM: "Zambia", ZW: "Zimbabwe", XX: "Unknown", T1: "Tor Exit",
};

export const check = new Hono<{ Bindings: EdgeEnv }>();

check.get("/check", (c) => {
  const cf: unknown = (c.req.raw as unknown as Record<string, unknown>)["cf"] || {};
  const code = cfStr(cf, "country").toUpperCase();
  const org = cfStr(cf, "asOrganization");
  return c.json({
    ip: c.req.header("CF-Connecting-IP") || "",
    countryCode: code,
    country: COUNTRY_NAMES[code] || "",
    regionName: cfStr(cf, "region"),
    city: cfStr(cf, "city"),
    isp: org,
    org: org,
    asName: cfAsn(cf),
    timezone: cfStr(cf, "timezone"),
  }, 200, { "Cache-Control": "no-store" });
});
