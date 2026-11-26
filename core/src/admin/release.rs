//! Ce qu'on lit dans le nom d'une release : résolution, codec, source, langue
//! de l'audio, HDR. Rien n'est inventé : un repère absent vaut « inconnu ».

use super::text::{contains_phrase, normalize, words};

/// Définition de l'image.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Resolution {
    /// 2160p, 4K, UHD.
    Uhd,
    /// 1080p.
    FullHd,
    /// 720p.
    Hd,
    /// 576p, 480p, DVD.
    Sd,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum VideoCodec {
    /// x265, H.265, HEVC.
    Hevc,
    Av1,
    /// x264, H.264, AVC.
    H264,
    /// XviD, DivX.
    Xvid,
    Unknown,
}

/// Origine de l'image.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ReleaseSource {
    /// Copie intégrale d'un Blu-ray.
    Remux,
    BluRay,
    WebDl,
    WebRip,
    /// « WEB » sans précision.
    Web,
    Hdtv,
    Dvd,
    /// Filmé en salle ou copie de travail (CAM, TS, TC, SCREENER) : écarté.
    Cam,
    Unknown,
}

/// Langue de l'audio, du point de vue d'un public francophone.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AudioLanguage {
    /// Plusieurs pistes dont le français de France (MULTi VFF, MULTi TRUEFRENCH).
    MultiVff,
    /// Plusieurs pistes, dont le français en principe (MULTi).
    Multi,
    /// Version française de France (VFF, TRUEFRENCH, VF2).
    Vff,
    /// Version française sans précision (FRENCH, VF, VFi).
    Vf,
    /// Version québécoise (VFQ).
    Vfq,
    /// Version originale sous-titrée (VOSTFR, SUBFRENCH).
    Vostfr,
    /// Aucun repère : probablement la version originale seule.
    Unknown,
}

/// Repères lus dans un nom de release.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ReleaseInfo {
    pub resolution: Resolution,
    pub codec: VideoCodec,
    pub source: ReleaseSource,
    pub language: AudioLanguage,
    /// HDR10, HDR10+ ou Dolby Vision.
    pub hdr: bool,
}

fn any(words: &[&str], candidates: &[&str]) -> bool {
    words.iter().any(|w| candidates.contains(w))
}

fn resolution(words: &[&str]) -> Resolution {
    if any(words, &["2160p", "4k", "uhd"]) {
        Resolution::Uhd
    } else if any(words, &["1080p", "1080i"]) {
        Resolution::FullHd
    } else if any(words, &["720p"]) {
        Resolution::Hd
    } else if any(words, &["576p", "480p", "dvdrip", "dvd", "xvid", "divx"]) {
        Resolution::Sd
    } else {
        Resolution::Unknown
    }
}

fn codec(words: &[&str]) -> VideoCodec {
    if any(words, &["x265", "h265", "hevc"]) || contains_phrase(words, &["h", "265"]) {
        VideoCodec::Hevc
    } else if any(words, &["av1"]) {
        VideoCodec::Av1
    } else if any(words, &["x264", "h264", "avc"]) || contains_phrase(words, &["h", "264"]) {
        VideoCodec::H264
    } else if any(words, &["xvid", "divx"]) {
        VideoCodec::Xvid
    } else {
        VideoCodec::Unknown
    }
}

fn source(words: &[&str]) -> ReleaseSource {
    if any(
        words,
        &[
            "cam", "camrip", "hdcam", "ts", "hdts", "telesync", "tc", "telecine", "scr",
            "screener", "dvdscr",
        ],
    ) {
        ReleaseSource::Cam
    } else if any(words, &["remux", "bdremux"]) {
        ReleaseSource::Remux
    } else if any(words, &["bluray", "bdrip", "brrip"]) || contains_phrase(words, &["blu", "ray"]) {
        ReleaseSource::BluRay
    } else if any(words, &["webdl"]) || contains_phrase(words, &["web", "dl"]) {
        ReleaseSource::WebDl
    } else if any(words, &["webrip"]) || contains_phrase(words, &["web", "rip"]) {
        ReleaseSource::WebRip
    } else if any(words, &["web"]) {
        ReleaseSource::Web
    } else if any(words, &["hdtv", "pdtv", "tvrip"]) {
        ReleaseSource::Hdtv
    } else if any(words, &["dvdrip", "dvd", "dvd5", "dvd9"]) {
        ReleaseSource::Dvd
    } else {
        ReleaseSource::Unknown
    }
}

fn language(words: &[&str]) -> AudioLanguage {
    let vff = any(words, &["vff", "truefrench", "vf2"]);
    if any(words, &["multi"]) {
        return if vff {
            AudioLanguage::MultiVff
        } else {
            AudioLanguage::Multi
        };
    }
    if vff {
        AudioLanguage::Vff
    } else if any(words, &["vfq"]) {
        AudioLanguage::Vfq
    } else if any(words, &["french", "vf", "vfi"]) {
        AudioLanguage::Vf
    } else if any(words, &["vostfr", "subfrench", "vost"]) {
        AudioLanguage::Vostfr
    } else {
        AudioLanguage::Unknown
    }
}

fn hdr(words: &[&str]) -> bool {
    any(words, &["hdr", "hdr10", "hdr10plus", "dv", "dovi"])
        || contains_phrase(words, &["dolby", "vision"])
}

/// Repères d'un nom de release.
pub(crate) fn parse(name: &str) -> ReleaseInfo {
    let normalized = normalize(name);
    let words = words(&normalized);
    ReleaseInfo {
        resolution: resolution(&words),
        codec: codec(&words),
        source: source(&words),
        language: language(&words),
        hdr: hdr(&words),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_a_typical_french_release() {
        let info = parse("Oppenheimer.2023.MULTi.TRUEFRENCH.2160p.WEB-DL.DV.HDR.H265-GRP");
        assert_eq!(info.resolution, Resolution::Uhd);
        assert_eq!(info.codec, VideoCodec::Hevc);
        assert_eq!(info.source, ReleaseSource::WebDl);
        assert_eq!(info.language, AudioLanguage::MultiVff);
        assert!(info.hdr);
    }

    #[test]
    fn does_not_read_titles_as_tags() {
        // « 24K Magic » n'est pas du 4K ; « Multiverse » n'est pas MULTi ;
        // « HDRip » n'est pas du HDR ; « DVDRip » n'est pas du Dolby Vision.
        assert_eq!(
            parse("Bruno Mars 24K Magic Live").resolution,
            Resolution::Unknown
        );
        assert_eq!(
            parse("Doctor.Strange.Multiverse.of.Madness.2022.FRENCH.1080p").language,
            AudioLanguage::Vf
        );
        assert!(!parse("Film.2020.HDRip.x264").hdr);
        assert!(!parse("Film.2020.FRENCH.DVDRip.XviD").hdr);
        assert_eq!(
            parse("Fantastic.Beasts.2016.FRENCH.BluRay").source,
            ReleaseSource::BluRay
        );
    }

    #[test]
    fn detects_cam_copies() {
        assert_eq!(
            parse("Film.2025.FRENCH.HDCAM.x264").source,
            ReleaseSource::Cam
        );
        assert_eq!(parse("Film 2025 TS 720p").source, ReleaseSource::Cam);
        assert_eq!(
            parse("Cameron.Story.2020.BluRay.1080p").source,
            ReleaseSource::BluRay
        );
    }

    #[test]
    fn reads_languages() {
        assert_eq!(
            parse("Film.2020.MULTi.1080p").language,
            AudioLanguage::Multi
        );
        assert_eq!(parse("Film.2020.VFF.1080p").language, AudioLanguage::Vff);
        assert_eq!(
            parse("Film.2020.FRENCH.VFQ.1080p").language,
            AudioLanguage::Vfq
        );
        assert_eq!(
            parse("Film.2020.VOSTFR.1080p").language,
            AudioLanguage::Vostfr
        );
        assert_eq!(
            parse("Film.2020.1080p.WEB.H264").language,
            AudioLanguage::Unknown
        );
    }

    #[test]
    fn reads_sources_and_codecs() {
        assert_eq!(
            parse("Film 2020 1080p BluRay REMUX AVC").source,
            ReleaseSource::Remux
        );
        assert_eq!(
            parse("Film 2020 1080p WEBRip x264").source,
            ReleaseSource::WebRip
        );
        assert_eq!(parse("Film 2020 1080p WEB H264").source, ReleaseSource::Web);
        assert_eq!(parse("Serie S01 HDTV").source, ReleaseSource::Hdtv);
        assert_eq!(parse("Film 2020 1080p WEB H.264").codec, VideoCodec::H264);
        assert_eq!(parse("Film 2020 1080p AV1").codec, VideoCodec::Av1);
        assert_eq!(parse("Film 2020 720p").resolution, Resolution::Hd);
    }
}
