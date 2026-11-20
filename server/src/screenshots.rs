//! Screenshot parsing and encoding. Only the worker decodes uploaded images.
use base64::{Engine, engine::general_purpose::STANDARD};
use image::{DynamicImage, ImageDecoder, ImageFormat};
use serde::{Deserialize, Serialize};
use std::io::Cursor;

pub(crate) const MAX_BASE64: usize = 400_000;
const MAX_BYTES: usize = 300_000;
const MAX_EDGE: u32 = 4096;
const MAX_PIXELS: u32 = 8_500_000;

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum Format {
    Jpeg,
    Webp,
}

impl Format {
    pub(crate) fn name(self) -> &'static str {
        match self {
            Self::Jpeg => "jpeg",
            Self::Webp => "webp",
        }
    }

    pub(crate) fn from_storage(name: &str) -> Option<Self> {
        match name {
            "jpeg" => Some(Self::Jpeg),
            "webp" => Some(Self::Webp),
            _ => None,
        }
    }
}

fn decode(encoded: &str, format: Option<Format>) -> Option<DynamicImage> {
    if encoded.len() > MAX_BASE64 {
        return None;
    }
    let bytes = STANDARD.decode(encoded).ok()?;
    if bytes.len() > MAX_BYTES {
        return None;
    }
    match format {
        None => decode_pixels(
            image::codecs::jpeg::JpegDecoder::new(Cursor::new(bytes)).ok()?,
            1440,
        ),
        Some(Format::Jpeg) => decode_pixels(
            image::codecs::jpeg::JpegDecoder::new(Cursor::new(bytes)).ok()?,
            MAX_EDGE,
        ),
        Some(Format::Webp) => {
            let decoder = image::codecs::webp::WebPDecoder::new(Cursor::new(bytes)).ok()?;
            if decoder.has_animation() {
                return None;
            }
            decode_pixels(decoder, MAX_EDGE)
        }
    }
}

fn decode_pixels(mut decoder: impl ImageDecoder, edge: u32) -> Option<DynamicImage> {
    let (width, height) = decoder.dimensions();
    if !(1..=edge).contains(&width) || !(1..=edge).contains(&height) || width * height > MAX_PIXELS
    {
        return None;
    }
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(edge);
    limits.max_image_height = Some(edge);
    limits.max_alloc = Some(40 * 1024 * 1024);
    limits.reserve(decoder.total_bytes()).ok()?;
    decoder.set_limits(limits).ok()?;
    DynamicImage::from_decoder(decoder).ok()
}

fn encode_jpeg(image: &DynamicImage, qualities: &[u8], max_bytes: usize) -> Option<String> {
    for &quality in qualities {
        let mut bytes = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut bytes, quality)
            .encode_image(image)
            .ok()?;
        if bytes.len() <= max_bytes {
            return Some(STANDARD.encode(bytes));
        }
    }
    None
}

/// Re-encode decoded pixels so uploaded metadata and container chunks stay private.
pub(crate) fn sanitize(encoded: &str, format: Option<Format>) -> Option<(String, Option<Format>)> {
    let image = decode(encoded, format)?;
    match format {
        None => encode_jpeg(&image.thumbnail(480, 480), &[60, 40, 25], 24 * 1024)
            .map(|jpeg| (jpeg, None)),
        Some(Format::Webp) => {
            let mut bytes = Cursor::new(Vec::new());
            image.write_to(&mut bytes, ImageFormat::WebP).ok()?;
            let bytes = bytes.into_inner();
            if bytes.len() <= MAX_BYTES {
                return Some((STANDARD.encode(bytes), format));
            }
            replay_jpeg(&image)
        }
        Some(Format::Jpeg) => replay_jpeg(&image),
    }
}

fn replay_jpeg(image: &DynamicImage) -> Option<(String, Option<Format>)> {
    let encoded = encode_jpeg(image, &[90, 78, 65], MAX_BYTES).or_else(|| {
        [1600, 1080, 800]
            .into_iter()
            .filter(|&edge| edge < image.width().max(image.height()))
            .find_map(|edge| encode_jpeg(&image.thumbnail(edge, edge), &[85, 70], MAX_BYTES))
    })?;
    Some((encoded, Some(Format::Jpeg)))
}

/// Fetch and transcode one sampled frame at a time in the worker.
pub(crate) fn load_model_jpeg(
    db: &rusqlite::Connection,
    id: &str,
) -> crate::Result<Option<String>> {
    use rusqlite::OptionalExtension;
    let stored: Option<(String, Option<String>)> = db
        .query_row(
            "SELECT jpeg_base64,image_format FROM observations WHERE id=? AND jpeg_base64 IS NOT NULL",
            [id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()
        .map_err(crate::storage_error)?;
    Ok(stored.and_then(|(encoded, format)| {
        let format = match format.as_deref() {
            Some(name) => Some(Format::from_storage(name)?),
            None => None,
        };
        model_jpeg(&encoded, format)
    }))
}

/// Model integrations already accept JPEG. Keep higher-resolution originals in replay only.
fn model_jpeg(encoded: &str, format: Option<Format>) -> Option<String> {
    let image = decode(encoded, format)?;
    if format.is_none() {
        return Some(encoded.to_owned());
    }
    let image = if image.width().max(image.height()) > 720 {
        image.thumbnail(720, 720)
    } else {
        image
    };
    encode_jpeg(&image, &[80, 65], MAX_BYTES)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn webp(width: u32, height: u32) -> String {
        let image = DynamicImage::new_rgb8(width, height);
        let mut output = Cursor::new(Vec::new());
        image.write_to(&mut output, ImageFormat::WebP).unwrap();
        STANDARD.encode(output.into_inner())
    }

    #[test]
    fn worker_restricts_format_dimensions_pixels_and_encoded_size() {
        let jpeg = encode_jpeg(&DynamicImage::new_rgb8(720, 1440), &[30], MAX_BYTES).unwrap();
        let wide_jpeg = encode_jpeg(&DynamicImage::new_rgb8(1500, 1), &[30], MAX_BYTES).unwrap();
        assert!(decode(&jpeg, None).is_some());
        assert!(decode(&wide_jpeg, None).is_none());
        assert!(decode(&wide_jpeg, Some(Format::Jpeg)).is_some());
        assert!(decode(&jpeg, Some(Format::Webp)).is_none());
        assert!(decode(&webp(4097, 1), Some(Format::Webp)).is_none());
        assert!(decode(&webp(3000, 3000), Some(Format::Webp)).is_none());
        assert!(decode("not base64!", None).is_none());
        assert!(decode(&"A".repeat(MAX_BASE64 + 1), Some(Format::Webp)).is_none());
    }
}
