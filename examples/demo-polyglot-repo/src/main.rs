// Risk evaluation engine — demo file with intentional violations

use std::collections::HashMap;

// VIOLATION: unsafe block without SAFETY comment
pub fn evaluate_risk(data: &[u8]) -> f64 {
    unsafe {
        let ptr = data.as_ptr();
        *ptr as f64 / 255.0
    }
}

// VIOLATION: .unwrap() in library code
pub fn parse_config(path: &str) -> HashMap<String, String> {
    let content = std::fs::read_to_string(path).unwrap();
    let mut map = HashMap::new();
    for line in content.lines() {
        let parts: Vec<&str> = line.splitn(2, '=').collect();
        map.insert(parts[0].unwrap().to_string(), parts[1].unwrap().to_string());
    }
    map
}

// VIOLATION: TODO without tracked issue
pub fn calculate_score(factors: &[f64]) -> f64 {
    // TODO: implement weighted scoring
    factors.iter().sum::<f64>() / factors.len() as f64
}
