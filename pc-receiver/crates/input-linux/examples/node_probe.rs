use std::os::unix::io::AsRawFd;

fn main() {
    let name = "pc-node-probe-v";
    let _dev = input_linux::UinputDevice::create(name).unwrap();

    // raw scan like find_event_node_by_name, with debug output
    let dir = std::fs::read_dir("/dev/input").unwrap();
    let mut candidates: Vec<String> = dir
        .flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.starts_with("event") && n[5..].chars().all(|c| c.is_ascii_digit()))
        .collect();
    candidates.sort_by_key(|n| n[5..].parse::<u32>().unwrap_or(0));
    println!("candidates: {} (last: {:?})", candidates.len(), candidates.last());

    let mut namebuf = [0u8; 128];
    for node in candidates.iter().rev().take(3) {
        let path = format!("/dev/input/{node}");
        match std::fs::File::open(&path) {
            Ok(f) => {
                let req = input_linux::uinput::eviocgname(namebuf.len());
                let n = unsafe { libc::ioctl(f.as_raw_fd(), req, namebuf.as_mut_ptr()) };
                println!(
                    "{node}: ioctl -> {n}, name = {:?}",
                    std::str::from_utf8(&namebuf[..n.max(0) as usize]).unwrap_or("<bad utf8>")
                );
            }
            Err(e) => println!("{node}: open failed: {e}"),
        }
    }
    std::thread::sleep(std::time::Duration::from_millis(500));
    println!("event_node() = {:?}", input_linux::uinput::find_event_node_by_name(name));
}
